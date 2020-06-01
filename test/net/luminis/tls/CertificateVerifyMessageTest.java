package net.luminis.tls;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static net.luminis.tls.TlsConstants.SignatureScheme.rsa_pss_rsae_sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class CertificateVerifyMessageTest {

    @Test
    void parseCertificateVerifyMessage() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("0f00001408040010000102030405060708090a0b0c0d0e0f");
        CertificateVerifyMessage msg = new CertificateVerifyMessage();
        msg.parse(ByteBuffer.wrap(rawData), 0, new TlsState());
        assertThat(msg.getSignatureScheme()).isEqualTo(rsa_pss_rsae_sha256);
        assertThat(msg.getSignature()).isEqualTo(new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 , 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f });
    }

    @Test
    void parseCertificateVerifyWithInvalidSignatureSchema() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("0f000014fafa0010000102030405060708090a0b0c0d0e0f");
        CertificateVerifyMessage msg = new CertificateVerifyMessage();

        assertThatThrownBy(() ->
                msg.parse(ByteBuffer.wrap(rawData), 0, new TlsState())
        ).isInstanceOf(DecodeErrorException.class);
    }

    @Test
    void parseCertificateVerifyWithMsgLengthTooSmall() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("0f00001008040010000102030405060708090a0b0c0d0e0f");
        CertificateVerifyMessage msg = new CertificateVerifyMessage();

        assertThatThrownBy(() ->
                msg.parse(ByteBuffer.wrap(rawData), 0, new TlsState())
        ).isInstanceOf(DecodeErrorException.class);
    }

    @Test
    void parseCertificateVerifyWithMsgLengthTooLong() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("0f00001d08040010000102030405060708090a0b0c0d0e0f");
        CertificateVerifyMessage msg = new CertificateVerifyMessage();

        assertThatThrownBy(() ->
                msg.parse(ByteBuffer.wrap(rawData), 0, new TlsState())
        ).isInstanceOf(DecodeErrorException.class);
    }

    @Test
    void parseCertificateVerifyWith() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("0f0000140804001d000102030405060708090a0b0c0d0e0f");
        CertificateVerifyMessage msg = new CertificateVerifyMessage();

        assertThatThrownBy(() ->
                msg.parse(ByteBuffer.wrap(rawData), 0, new TlsState())
        ).isInstanceOf(DecodeErrorException.class);
    }
}
