package net.luminis.tls.handshake;

import net.luminis.tls.*;
import net.luminis.tls.alert.*;
import net.luminis.tls.extension.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.*;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.*;
import java.util.stream.Collectors;

import static net.luminis.tls.TlsConstants.SignatureScheme.rsa_pss_rsae_sha256;


public class TlsClientEngine implements TrafficSecrets, ClientMessageProcessor {

    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    enum Status {
        Initial,
        ClientHelloSent,
        ServerHelloReceived,
        EncryptedExtensionsReceived,
        CertificateReceived,
        CertificateVerifyReceived,
        Finished
    }

    private final ClientMessageSender sender;
    private final TlsStatusEventHandler statusHandler;
    private String serverName;
    private String ecCurve = "secp256r1";
    private ECPublicKey publicKey;
    private ECPrivateKey privateKey;
    private boolean compatibilityMode;
    private List<TlsConstants.CipherSuite> supportedCiphers;
    private TlsConstants.CipherSuite selectedCipher;
    private List<Extension> extensions;
    private Status status = Status.Initial;
    private ClientHello clientHello;
    private TlsState state;
    private TranscriptHash transcriptHash;
    private List<TlsConstants.SignatureScheme> supportedSignatures;
    private X509Certificate serverCertificate;
    private List<X509Certificate> serverCertificateChain = Collections.emptyList();
    private X509TrustManager customTrustManager;
    private NewSessionTicket newSessionTicket;
    private HostnameVerifier hostnameVerifier;
    private List<NewSessionTicket> obtainedNewSessionTickets;
    private boolean pskAccepted = false;

    public TlsClientEngine(ClientMessageSender clientMessageSender, TlsStatusEventHandler tlsStatusHandler) {
        sender = clientMessageSender;
        statusHandler = tlsStatusHandler;
        supportedCiphers = new ArrayList<>();
        extensions = new ArrayList<>();
        hostnameVerifier = new DefaultHostnameVerifier();
        obtainedNewSessionTickets = new ArrayList<>();
    }

    public void startHandshake() throws IOException {
        startHandshake(List.of(rsa_pss_rsae_sha256));
    }

    public void startHandshake(List<TlsConstants.SignatureScheme> signatureSchemes) throws IOException {
        supportedSignatures = signatureSchemes;
        generateKeys();
        if (serverName == null || supportedCiphers.isEmpty()) {
            throw new IllegalStateException("not all mandatory properties are set");
        }

        transcriptHash = new TranscriptHash(32);
        if (newSessionTicket != null) {
            state = new TlsState(transcriptHash, newSessionTicket.getPSK());
            extensions.add(new ClientHelloPreSharedKeyExtension(state, newSessionTicket));
        }
        else {
            state = new TlsState(transcriptHash);
        }

        clientHello = new ClientHello(serverName, publicKey, compatibilityMode, supportedCiphers, supportedSignatures, extensions);
        extensions = clientHello.getExtensions();
        sender.send(clientHello);
        status = Status.ClientHelloSent;

        transcriptHash.record(clientHello);
        state.clientHelloSend(privateKey, clientHello.getBytes());

        statusHandler.earlySecretsKnown();
    }

    /**
     * Updates the (handshake) state with a received Server Hello message.
     * @param serverHello
     * @throws MissingExtensionAlert
     */
    public void received(ServerHello serverHello) throws MissingExtensionAlert, IllegalParameterAlert {
        boolean containsSupportedVersionExt = serverHello.getExtensions().stream().anyMatch(ext -> ext instanceof SupportedVersionsExtension);
        boolean containsKeyExt = serverHello.getExtensions().stream().anyMatch(ext -> ext instanceof PreSharedKeyExtension || ext instanceof KeyShareExtension);
        // https://tools.ietf.org/html/rfc8446#section-4.1.3
        // "All TLS 1.3 ServerHello messages MUST contain the "supported_versions" extension.
        // Current ServerHello messages additionally contain either the "pre_shared_key" extension or the "key_share"
        // extension, or both (when using a PSK with (EC)DHE key establishment)."
        if (! containsSupportedVersionExt || !containsKeyExt) {
            throw new MissingExtensionAlert();
        }

        // https://tools.ietf.org/html/rfc8446#section-4.2.1
        // "A server which negotiates TLS 1.3 MUST respond by sending a "supported_versions" extension containing the selected version value (0x0304)."
        short tlsVersion = serverHello.getExtensions().stream()
                .filter(extension -> extension instanceof SupportedVersionsExtension)
                .map(extension -> ((SupportedVersionsExtension) extension).getTlsVersion())
                .findFirst()
                .get();
        if (tlsVersion != 0x0304) {
            throw new IllegalParameterAlert("invalid tls version");
        }

        // https://tools.ietf.org/html/rfc8446#section-4.2
        // "If an implementation receives an extension which it recognizes and which is not specified for the message in
        // which it appears, it MUST abort the handshake with an "illegal_parameter" alert."
        if (serverHello.getExtensions().stream()
            .anyMatch(ext -> ! (ext instanceof SupportedVersionsExtension) &&
                    ! (ext instanceof PreSharedKeyExtension) &&
                    ! (ext instanceof KeyShareExtension))) {
            throw new IllegalParameterAlert("illegal extension in server hello");
        }

        Optional<KeyShareExtension.KeyShareEntry> keyShare = serverHello.getExtensions().stream()
                .filter(extension -> extension instanceof KeyShareExtension)
                // In the context of a server hello, the key share extension contains exactly one key share entry
                .map(extension -> ((KeyShareExtension) extension).getKeyShareEntries().get(0))
                .findFirst();

        Optional<Extension> preSharedKey = serverHello.getExtensions().stream()
                .filter(extension -> extension instanceof ServerPreSharedKeyExtension)
                .findFirst();

        // https://tools.ietf.org/html/rfc8446#section-4.1.3
        // "ServerHello messages additionally contain either the "pre_shared_key" extension or the "key_share" extension,
        // or both (when using a PSK with (EC)DHE key establishment)."
        if (keyShare.isEmpty() && preSharedKey.isEmpty()) {
            throw new MissingExtensionAlert(" either the pre_shared_key extension or the key_share extension must be present");
        }

        if (preSharedKey.isPresent()) {
            // https://tools.ietf.org/html/rfc8446#section-4.2.11
            // "In order to accept PSK key establishment, the server sends a "pre_shared_key" extension indicating the selected identity."
            pskAccepted = true;
            System.out.println("JOH! PSK accepted!");
        }

        if (! supportedCiphers.contains(serverHello.getCipherSuite())) {
            // https://tools.ietf.org/html/rfc8446#section-4.1.3
            // "A client which receives a cipher suite that was not offered MUST abort the handshake with an "illegal_parameter" alert."
            throw new IllegalParameterAlert("cipher suite does not match");
        }
        selectedCipher = serverHello.getCipherSuite();

        if (preSharedKey.isPresent()) {
            state.setPskSelected(((ServerPreSharedKeyExtension) preSharedKey.get()).getSelectedIdentity());
            Logger.debug("Server has accepted PSK key establishment");
        }
        if (keyShare.isPresent()) {
            state.setServerSharedKey(keyShare.get().getKey());
        }
        transcriptHash.record(serverHello);
        state.serverHelloReceived(serverHello.getBytes());
        status = Status.ServerHelloReceived;
        statusHandler.handshakeSecretsKnown();
    }

    public void received(EncryptedExtensions encryptedExtensions) throws TlsProtocolException {
        if (status != Status.ServerHelloReceived) {
            // https://tools.ietf.org/html/rfc8446#section-4.3.1
            // "the server MUST send the EncryptedExtensions message immediately after the ServerHello message"
            throw new UnexpectedMessageAlert("unexpected encrypted extensions message");
        }

        List<Class> clientExtensionTypes = extensions.stream()
                .map(extension -> extension.getClass()).collect(Collectors.toList());
        boolean allClientResponses = encryptedExtensions.getExtensions().stream()
                .filter(ext -> ! (ext instanceof UnknownExtension))
                .allMatch(ext -> clientExtensionTypes.contains(ext.getClass()));
        if (! allClientResponses) {
            // https://tools.ietf.org/html/rfc8446#section-4.2
            // "Implementations MUST NOT send extension responses if the remote endpoint did not send the corresponding
            // extension requests, with the exception of the "cookie" extension in the HelloRetryRequest. Upon receiving
            // such an extension, an endpoint MUST abort the handshake with an "unsupported_extension" alert."
            throw new UnsupportedExtensionAlert("extension response to missing request");
        }

        int uniqueExtensions = encryptedExtensions.getExtensions().stream()
                .map(extension -> extension.getClass())
                .collect(Collectors.toSet())
                .size();
        if (uniqueExtensions != encryptedExtensions.getExtensions().size()) {
            // "There MUST NOT be more than one extension of the same type in a given extension block."
            throw new UnsupportedExtensionAlert("duplicate extensions not allowed");
        }

        transcriptHash.record(encryptedExtensions);
        status = Status.EncryptedExtensionsReceived;
        statusHandler.extensionsReceived(encryptedExtensions.getExtensions());
    }

    public void received(CertificateMessage certificateMessage) throws TlsProtocolException {
        if (status != Status.EncryptedExtensionsReceived) {
            // https://tools.ietf.org/html/rfc8446#section-4.4
            // "TLS generally uses a common set of messages for authentication, key confirmation, and handshake
            //   integrity: Certificate, CertificateVerify, and Finished.  (...)  These three messages are always
            //   sent as the last messages in their handshake flight."
            throw new UnexpectedMessageAlert("unexpected certificate message");
        }

        if (certificateMessage.getRequestContext().length > 0) {
            // https://tools.ietf.org/html/rfc8446#section-4.4.2
            // "If this message is in response to a CertificateRequest, the value of certificate_request_context in that
            // message. Otherwise (in the case of server authentication), this field SHALL be zero length."
            throw new IllegalParameterAlert("certificate request context should be zero length");
        }
        if (certificateMessage.getEndEntityCertificate() == null) {
            throw new IllegalParameterAlert("missing certificate");
        }

        serverCertificate = certificateMessage.getEndEntityCertificate();
        serverCertificateChain = certificateMessage.getCertificateChain();
        transcriptHash.record(certificateMessage);
        status = Status.CertificateReceived;
    }

    public void received(CertificateVerifyMessage certificateVerifyMessage) throws TlsProtocolException {
        if (status != Status.CertificateReceived) {
            // https://tools.ietf.org/html/rfc8446#section-4.4.3
            // "When sent, this message MUST appear immediately after the Certificate message and immediately prior to
            // the Finished message."
            throw new UnexpectedMessageAlert("unexpected certificate verify message");
        }

        TlsConstants.SignatureScheme signatureScheme = certificateVerifyMessage.getSignatureScheme();
        if (!supportedSignatures.contains(signatureScheme)) {
            // https://tools.ietf.org/html/rfc8446#section-4.4.3
            // "If the CertificateVerify message is sent by a server, the signature algorithm MUST be one offered in
            // the client's "signature_algorithms" extension"
            throw new IllegalParameterAlert("signature scheme does not match");
        }

        byte[] signature = certificateVerifyMessage.getSignature();
        if (!verifySignature(signature, signatureScheme, serverCertificate, transcriptHash.getHash(TlsConstants.HandshakeType.certificate))) {
            throw new DecryptErrorAlert("signature verification fails");
        }

        // Now the certificate signature has been validated, check the certificate validity
        checkCertificateValidity(serverCertificateChain);
        if (!hostnameVerifier.verify(serverName, serverCertificate)) {
            throw new CertificateUnknownAlert("servername does not match");
        }

        transcriptHash.record(certificateVerifyMessage);
        status = Status.CertificateVerifyReceived;
    }

    public void received(FinishedMessage finishedMessage) throws DecryptErrorAlert, UnexpectedMessageAlert, IOException {
        Status expectedStatus;
        if (pskAccepted) {
            expectedStatus = Status.EncryptedExtensionsReceived;
        }
        else {
            expectedStatus = Status.CertificateVerifyReceived;
        }
        if (status != expectedStatus) {
            throw new UnexpectedMessageAlert("unexpected finished message");
        }

        transcriptHash.recordServer(finishedMessage);

        // https://tools.ietf.org/html/rfc8446#section-4.4
        // "   | Mode      | Handshake Context       | Base Key                    |
        //     +-----------+-------------------------+-----------------------------+
        //     | Server    | ClientHello ... later   | server_handshake_traffic_   |
        //     |           | of EncryptedExtensions/ | secret                      |
        //     |           | CertificateRequest      |                             |"
        byte[] serverHmac = computeFinishedVerifyData(transcriptHash.getHash(TlsConstants.HandshakeType.certificate_verify), state.getServerHandshakeTrafficSecret());
        // https://tools.ietf.org/html/rfc8446#section-4.4
        // "Recipients of Finished messages MUST verify that the contents are correct and if incorrect MUST terminate the connection with a "decrypt_error" alert."
        if (!Arrays.equals(finishedMessage.getVerifyData(), serverHmac)) {
            throw new DecryptErrorAlert("incorrect finished message");
        }

        // https://tools.ietf.org/html/rfc8446#section-4.4
        // "   | Mode      | Handshake Context       | Base Key                    |
        //     | Client    | ClientHello ... later   | client_handshake_traffic_   |
        //     |           | of server               | secret                      |
        //     |           | Finished/EndOfEarlyData |                             |"
        byte[] clientHmac = computeFinishedVerifyData(transcriptHash.getServerHash(TlsConstants.HandshakeType.finished), state.getClientHandshakeTrafficSecret());
        FinishedMessage clientFinished = new FinishedMessage(clientHmac);
        sender.send(clientFinished);

        transcriptHash.recordClient(clientFinished);
        state.computeApplicationSecrets();
        status = Status.Finished;
        statusHandler.handshakeFinished();
    }

    @Override
    public void received(NewSessionTicketMessage nst) {
        NewSessionTicket ticket = new NewSessionTicket(state, nst);
        obtainedNewSessionTickets.add(ticket);
        statusHandler.newSessionTicketReceived(ticket);
    }


    private void generateKeys() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
            keyPairGenerator.initialize(new ECGenParameterSpec(ecCurve));

            KeyPair keyPair = keyPairGenerator.genKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            publicKey = (ECPublicKey) keyPair.getPublic();
        } catch (NoSuchAlgorithmException e) {
            // Invalid runtime
            throw new RuntimeException("missing key pair generator algorithm EC");
        } catch (InvalidAlgorithmParameterException e) {
            // Impossible, would be programming error
            throw new RuntimeException();
        }
    }

    protected boolean verifySignature(byte[] signatureToVerify, TlsConstants.SignatureScheme signatureScheme, Certificate certificate, byte[] transcriptHash) {
        // https://tools.ietf.org/html/rfc8446#section-4.4.3
        // "The digital signature is then computed over the concatenation of:
        //   -  A string that consists of octet 32 (0x20) repeated 64 times
        //   -  The context string
        //   -  A single 0 byte which serves as the separator
        //   -  The content to be signed"
        ByteBuffer contentToSign = ByteBuffer.allocate(64 + "TLS 1.3, server CertificateVerify".getBytes(ISO_8859_1).length + 1 + transcriptHash.length);
        for (int i = 0; i < 64; i++) {
            contentToSign.put((byte) 0x20);
        }
        // "The context string for a server signature is
        //   "TLS 1.3, server CertificateVerify". "
        contentToSign.put("TLS 1.3, server CertificateVerify".getBytes(ISO_8859_1));
        contentToSign.put((byte) 0x00);
        // "The content that is covered
        //   under the signature is the hash output as described in Section 4.4.1,
        //   namely:
        //      Transcript-Hash(Handshake Context, Certificate)"
        contentToSign.put(transcriptHash);

        Signature signatureAlgorithm = null;
        // https://tools.ietf.org/html/rfc8446#section-9.1
        // "A TLS-compliant application MUST support digital signatures with rsa_pkcs1_sha256 (for certificates),
        // rsa_pss_rsae_sha256 (for CertificateVerify and certificates), and ecdsa_secp256r1_sha256."
        if (signatureScheme.equals(rsa_pss_rsae_sha256)) {
            try {
                signatureAlgorithm = Signature.getInstance("RSASSA-PSS");
                signatureAlgorithm.setParameter(new PSSParameterSpec("SHA-256", "MGF1", new MGF1ParameterSpec("SHA-256"), 32, 1));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Missing RSASSA-PSS support");
            } catch (InvalidAlgorithmParameterException e) {
                // Fairly impossible (because the parameters is hard coded)
                throw new RuntimeException(e);
            }
        } else {
            // Bad lock, not yet supported.
            throw new RuntimeException("Signature algorithm (verification) not supported " + signatureScheme);
        }
        boolean verified = false;
        try {
            signatureAlgorithm.initVerify(certificate);
            signatureAlgorithm.update(contentToSign.array());
            verified = signatureAlgorithm.verify(signatureToVerify);
        } catch (InvalidKeyException e) {
            Logger.debug("Certificate verify: invalid key.");
        } catch (SignatureException e) {
            Logger.debug("Certificate verify: invalid signature.");
        }
        return verified;
    }

    protected void checkCertificateValidity(List<X509Certificate> certificates) throws BadCertificateAlert {
        try {
            if (customTrustManager != null) {
                customTrustManager.checkServerTrusted(certificates.toArray(X509Certificate[]::new), "RSA");
            }
            else {
                // https://docs.oracle.com/en/java/javase/11/docs/specs/security/standard-names.html#trustmanagerfactory-algorithms
                // "...that validate certificate chains according to the rules defined by the IETF PKIX working group in RFC 5280 or its successor"
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
                trustManagerFactory.init((KeyStore) null);
                X509TrustManager trustMgr = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];
                trustMgr.checkServerTrusted(certificates.toArray(X509Certificate[]::new), "RSA");
                // If it gets here, the certificates are ok.
            }
        } catch (NoSuchAlgorithmException e) {
            // Impossible, as we're using the trust managers default algorithm
            throw new RuntimeException("unsupported trust manager algorithm");
        } catch (KeyStoreException e) {
            // Impossible, as we're using the default (JVM) keystore
            throw new RuntimeException("keystore exception");
        } catch (CertificateException e) {
            throw new BadCertificateAlert(extractReason(e).orElse("certificate validation failed"));
        }
    }

    // https://tools.ietf.org/html/rfc8446#section-4.4.4
    protected byte[] computeFinishedVerifyData(byte[] transcriptHash, byte[] baseKey) {
        short hashLength = state.getHashLength();
        byte[] finishedKey = state.hkdfExpandLabel(baseKey, "finished", "", hashLength);
        String macAlgorithmName = "HmacSHA" + (hashLength * 8);
        SecretKeySpec hmacKey = new SecretKeySpec(finishedKey, macAlgorithmName);

        try {
            Mac hmacAlgorithm = Mac.getInstance(macAlgorithmName);
            hmacAlgorithm.init(hmacKey);
            hmacAlgorithm.update(transcriptHash);
            byte[] hmac = hmacAlgorithm.doFinal();
            return hmac;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Missing " + macAlgorithmName + " support");
        } catch (InvalidKeyException e) {
            throw new RuntimeException();
        }
    }

    private Optional<String> extractReason(CertificateException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof CertPathValidatorException) {
            return Optional.of(cause.getMessage() + ": " + ((CertPathValidatorException) cause).getReason());
        }
        else {
            return Optional.empty();
        }
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public void setCompatibilityMode(boolean compatibilityMode) {
        this.compatibilityMode = compatibilityMode;
    }

    public void addSupportedCiphers(List<TlsConstants.CipherSuite> supportedCiphers) {
        this.supportedCiphers.addAll(supportedCiphers);
    }

    public void addExtensions(List<Extension> extensions) {
        this.extensions.addAll(extensions);
    }

    public void add(Extension extension) {
        extensions.add(extension);
    }

    public void setTrustManager(X509TrustManager customTrustManager) {
        this.customTrustManager = customTrustManager;
    }

    /**
     * Add ticket to use for a new session.
     * @param newSessionTicket
     */
    public void setNewSessionTicket(NewSessionTicket newSessionTicket) {
        this.newSessionTicket = newSessionTicket;
    }

    public TlsConstants.CipherSuite getSelectedCipher() {
        if (selectedCipher != null) {
            return selectedCipher;
        }
        else {
            throw new IllegalStateException("No (valid) server hello received yet");
        }
    }

    public byte[] getClientEarlyTrafficSecret() {
        if (state != null) {
            return state.getClientEarlyTrafficSecret();
        }
        else {
            throw new IllegalStateException("Traffic secret not yet available");
        }
    }

    public byte[] getClientHandshakeTrafficSecret() {
        if (state != null) {
            return state.getClientHandshakeTrafficSecret();
        }
        else {
            throw new IllegalStateException("Traffic secret not yet available");
        }
    }

    public byte[] getServerHandshakeTrafficSecret() {
        if (state != null) {
            return state.getServerHandshakeTrafficSecret();
        }
        else {
            throw new IllegalStateException("Traffic secret not yet available");
        }
    }

    public byte[] getClientApplicationTrafficSecret() {
        if (state != null) {
            return state.getClientApplicationTrafficSecret();
        }
        else {
            throw new IllegalStateException("Traffic secret not yet available");
        }
    }

    public byte[] getServerApplicationTrafficSecret() {
        if (state != null) {
            return state.getServerApplicationTrafficSecret();
        }
        else {
            throw new IllegalStateException("Traffic secret not yet available");
        }
    }

    /**
     * Returns tickets provided by the current connection.
     * @return
     */
    public List<NewSessionTicket> getNewSessionTickets() {
        return obtainedNewSessionTickets;
    }

    public List<X509Certificate> getServerCertificateChain() {
        return serverCertificateChain;
    }

    public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
        if (hostnameVerifier != null) {
            this.hostnameVerifier = hostnameVerifier;
        }
    }

    public boolean handshakeFinished() {
        return status == Status.Finished;
    }


    // TODO: remove this
    public TlsState getState() {
        return state;
    }
}