package com.kubemind.cluster;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void roundTripsPlaintext() {
        var crypto = new CryptoService(randomKey());
        String secret = "apiVersion: v1\nkind: Config\nusers:\n- name: admin\n  user:\n    token: sk-123";

        String encrypted = crypto.encrypt(secret);

        assertThat(encrypted).doesNotContain("sk-123");
        assertThat(crypto.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void producesDifferentCiphertextEachTime() {
        var crypto = new CryptoService(randomKey());
        // Random IV per call — identical plaintexts must not produce identical ciphertexts.
        assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
    }

    @Test
    void failsDecryptionWithWrongKey() {
        var a = new CryptoService(randomKey());
        var b = new CryptoService(randomKey());
        String encrypted = a.encrypt("secret");

        assertThatThrownBy(() -> b.decrypt(encrypted))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Decryption failed");
    }

    @Test
    void isUnavailableWithoutKeyAndFailsLoudly() {
        var crypto = new CryptoService("");

        assertThat(crypto.isAvailable()).isFalse();
        assertThatThrownBy(() -> crypto.encrypt("x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("KUBEMIND_ENCRYPTION_KEY");
    }

    @Test
    void rejectsMalformedKeys() {
        assertThatThrownBy(() -> new CryptoService("not-base64!!"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CryptoService(Base64.getEncoder().encodeToString(new byte[16])))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 bytes");
    }
}
