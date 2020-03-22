package net.luminis.tls;

import net.luminis.tls.extension.KeyShareExtension;
import net.luminis.tls.extension.PskKeyExchangeModesExtension;
import net.luminis.tls.extension.ServerNameExtension;
import net.luminis.tls.extension.SignatureAlgorithmsExtension;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static net.luminis.tls.TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256;
import static net.luminis.tls.TlsConstants.CipherSuite.TLS_AES_256_GCM_SHA384;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class ClientHelloTest {

    @Test
    void parseClientHello() throws Exception {
        //                                      length v1.2 random
        byte[] data = ByteUtils.hexToBytes(("01 000103 0303 2411ec38adb041713ca81a04182a655b567ecc8c4935e082ec20bb233d57aff2"
                //    cipher         comp ext's length
                + "00 0004 1301 1302 0100 00d6"
                // server name extension                version ext    supported groups extension
                + "0000000e000c0000096c6f63616c686f7374 002b0003020304 000a000400020017"
                // signature algorithms extension
                + "000d00140012040308040401050308050501080606010201"
                // key share extension
                + "00330047004500170041045d58e52e3deee2e8b78ec51e2d0cedb5080c8244bd3f651219cc48f3d3d404399d6748ab3eaaca0e32b927fc5e8107628e636b614cab332d8637c1d61caccdda"
                // psk key exchange modes extension
                + "002d00020101"
                // unknown extension (QUIC transport parameters)
                + "ffa500340032000100048000ea6000040004802625a0000500048003d090000600048003d090000700048003d09000080001010009000101"
                // unknown extension (ec_point_formats)  alpn extension
                + "000b000403000102" +                  "0010000800060568712d3234").replaceAll(" ", ""));
        ClientHello ch = new ClientHello(ByteBuffer.wrap(data));

        assertThat(ch.getClientRandom()).isEqualTo(ByteUtils.hexToBytes("2411ec38adb041713ca81a04182a655b567ecc8c4935e082ec20bb233d57aff2"));
        assertThat(ch.getCipherSuites()).containsExactly(TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384);
        assertThat(ch.getExtensions()).hasAtLeastOneElementOfType(ServerNameExtension.class);
        assertThat(ch.getExtensions()).hasAtLeastOneElementOfType(SignatureAlgorithmsExtension.class);
        assertThat(ch.getExtensions()).hasAtLeastOneElementOfType(KeyShareExtension.class);
        assertThat(ch.getExtensions()).hasAtLeastOneElementOfType(PskKeyExchangeModesExtension.class);
        assertThat(ch.getExtensions()).hasAtLeastOneElementOfType(ApplicationLayerProtocolNegotiationExtension.class);
        assertThat(ch.getExtensions()).hasSize(9);
    }

    @Test
    void parseMinimalClientHello() throws Exception {
        byte[] data = ByteUtils.hexToBytes(("01 00002b 0303 2411ec38adb041713ca81a04182a655b567ecc8c4935e082ec20bb233d57aff2"
                //    cipher    comp ext's length
                + "00 0002 1301 0100 0000").replaceAll(" ", ""));
        ClientHello ch = new ClientHello(ByteBuffer.wrap(data));
        assertThat(ch.getClientRandom()).isEqualTo(ByteUtils.hexToBytes("2411ec38adb041713ca81a04182a655b567ecc8c4935e082ec20bb233d57aff2"));
        assertThat(ch.getCipherSuites()).containsExactly(TLS_AES_128_GCM_SHA256);
        assertThat(ch.getExtensions()).hasSize(0);
    }

    @Test
    void parseClientHelloWithInvalidLength() throws Exception {
        byte[] data = ByteUtils.hexToBytes(("01 00092b 0303 2411ec38adb041713ca81a04182a655b567ecc8c4935e082ec20bb233d57aff2"
                //    cipher    comp ext's length
                + "00 0002 1301 0100 0000").replaceAll(" ", ""));

        assertThatThrownBy(() ->
                new ClientHello(ByteBuffer.wrap(data))
        ).isInstanceOf(DecodeErrorException.class);
    }

    @Test
    void parseClientHelloWithIncorrectClientRamdom() throws Exception {
        byte[] data = ByteUtils.hexToBytes(("01 00092b 0303 2411ec38adb04171"  // 8 bytes instead of 32
                //    cipher    comp ext's length
                + "00 0002 1301 0100 0000").replaceAll(" ", ""));

        assertThatThrownBy(() ->
                new ClientHello(ByteBuffer.wrap(data))
        ).isInstanceOf(DecodeErrorException.class);
    }

    @Test
    void parseClientHelloWithInValidCipher() throws Exception {
        byte[] data = ByteUtils.hexToBytes(("01 00002b 0303 2411ec38adb041713ca81a04182a655b567ecc8c4935e082ec20bb233d57aff2"
                //    cipher    comp ext's length
                + "00 0002 130f 0100 0000").replaceAll(" ", ""));

        ClientHello ch = new ClientHello(ByteBuffer.wrap(data));
        assertThat(ch.getCipherSuites()).isEmpty();
    }
}