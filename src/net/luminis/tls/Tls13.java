package net.luminis.tls;

import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

// TODO: throw exception when input stream is at end-of-file and returns -1...

public class Tls13 {

    public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    static String server =
            // "tls13.pinterjann.is";      // -> Only x25519
            // "www.wolfssl.com";          // -> Handshake failure  -> geen 1.3 support ;-)
            // "tls.ctf.network";          // -> Only x25519
            // "tls13.baishancloud.com";   // -> Handshake failure  -> ook via browser, blijkbaar geen 1.3 support
            // "mew.org";                  // -> Crypto error: javax.crypto.AEADBadTagException: Tag mismatch!
            // "antagonist.nl";            // -> Handshake failure  -> geen 1.3 support
            // "rustls.jbp.io";   // Yes!
            "gmail.com";  //



    public static void main(String[] args) throws Exception {
        Logger.enableDebugLogging(true);
        startTlsWithServer(server, 443);
    }

    public static void startTlsWithServer(String serverName, int serverPort) throws Exception {

        ECKey[] keys = generateKeys("secp256r1");
        ECPrivateKey privateKey = (ECPrivateKey) keys[0];
        ECPublicKey publicKey = (ECPublicKey) keys[1];

        Socket socket = new Socket(serverName, serverPort);
        OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());

        TlsSession tlsSession = new TlsSession(privateKey, publicKey, input, outputStream, serverName);
        tlsSession.setNewSessionTicketCallback(ticket -> {
                    File savedSessionTicket = new File("lastSessionTicket.bin");
                    NewSessionTicket newSessionTicket = tlsSession.getNewSessionTicket(0);
                    try {
                        Files.write(savedSessionTicket.toPath(), newSessionTicket.serialize(), StandardOpenOption.CREATE);
                    } catch (IOException e) {
                        System.err.println("Saving new session ticket failed: " + e);
                    }
                });
        tlsSession.sendApplicationData("GET / HTTP/1.1\r\n\r\n".getBytes());
    }

    public static ECKey[] generateKeys(String ecCurve) throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec(ecCurve));

        KeyPair keyPair = keyPairGenerator.genKeyPair();
        ECPrivateKey privKey = (ECPrivateKey) keyPair.getPrivate();
        ECPublicKey pubKey = (ECPublicKey) keyPair.getPublic();

        return new ECKey[] { privKey, pubKey };
    }

}
