package com.kubemind.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for kubeconfigs at rest. The key is env-provided
 * (KUBEMIND_ENCRYPTION_KEY, base64-encoded 32 bytes — generate with
 * `openssl rand -base64 32`) and never hardcoded.
 *
 * Without a key the app still boots (the default in-cluster/ambient client
 * needs no stored credentials) but registering remote clusters fails with a
 * clear message instead of silently storing plaintext.
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key; // null => encryption unavailable
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${kubemind.encryption.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            log.warn("KUBEMIND_ENCRYPTION_KEY is not set - remote cluster registration is disabled "
                + "until a key is provided (generate one with: openssl rand -base64 32).");
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("KUBEMIND_ENCRYPTION_KEY is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                "KUBEMIND_ENCRYPTION_KEY must decode to exactly 32 bytes (got " + raw.length
                + "). Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isAvailable() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_BITS, all, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(all, GCM_IV_BYTES, all.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Wrong key or corrupted data — never include the payload in the message.
            throw new IllegalStateException("Decryption failed - was KUBEMIND_ENCRYPTION_KEY changed?", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                "KUBEMIND_ENCRYPTION_KEY is not configured. Generate one with "
                + "'openssl rand -base64 32' and set it in backend/.env");
        }
    }
}
