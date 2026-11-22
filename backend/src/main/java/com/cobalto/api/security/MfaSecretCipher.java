package com.cobalto.api.security;

import io.jsonwebtoken.io.Decoders;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!migrate")
public class MfaSecretCipher {
    private static final byte[] KEY_DERIVATION_CONTEXT =
        "cobalto/mfa/aes-256-gcm/v1".getBytes(StandardCharsets.UTF_8);
    private static final int KEY_ID_LENGTH = 8;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private final List<EncryptionKey> encryptionKeys;
    private final SecureRandom secureRandom = new SecureRandom();

    public MfaSecretCipher(
        @Value("${cobalto.security.mfa-encryption-keys}") String encodedKeys
    ) {
        String[] configuredKeys = encodedKeys.split(",", -1);
        if (configuredKeys.length > 256) {
            throw new IllegalArgumentException("At most 256 MFA encryption keys can be configured");
        }
        encryptionKeys = new ArrayList<>(configuredKeys.length);
        for (String encodedKey : configuredKeys) {
            byte[] rootKey = Decoders.BASE64.decode(encodedKey.trim());
            if (rootKey.length < 32) {
                throw new IllegalArgumentException("MFA encryption keys must contain at least 32 bytes");
            }
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(rootKey, "HmacSHA256"));
                byte[] keyId = java.util.Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256").digest(rootKey), KEY_ID_LENGTH);
                byte[] encryptionKey = mac.doFinal(KEY_DERIVATION_CONTEXT);
                if (encryptionKeys.stream().anyMatch(key ->
                    MessageDigest.isEqual(key.id(), keyId))) {
                    throw new IllegalArgumentException("MFA encryption key is configured more than once");
                }
                encryptionKeys.add(new EncryptionKey(keyId, encryptionKey));
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
            }
        }
    }

    public byte[] encrypt(String userId, String secret) {
        EncryptionKey activeKey = encryptionKeys.get(0);
        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(activeKey.value(), "AES"),
                new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(KEY_ID_LENGTH + nonce.length + encrypted.length)
                .put(activeKey.id()).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt MFA secret", exception);
        }
    }

    public String decrypt(String userId, byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length <= KEY_ID_LENGTH + NONCE_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted MFA secret");
        }
        byte[] keyId = new byte[KEY_ID_LENGTH];
        ByteBuffer.wrap(ciphertext).get(keyId);
        EncryptionKey selectedKey = findKey(keyId);
        byte[] encryptionKey = selectedKey.value();
        byte[] nonce = new byte[NONCE_LENGTH];
        byte[] encrypted = new byte[ciphertext.length - KEY_ID_LENGTH - NONCE_LENGTH];
        ByteBuffer.wrap(ciphertext).position(KEY_ID_LENGTH).get(nonce).get(encrypted);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt MFA secret", exception);
        }
    }

    public byte[] reencrypt(String userId, byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length <= KEY_ID_LENGTH + NONCE_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted MFA secret");
        }
        byte[] keyId = new byte[KEY_ID_LENGTH];
        ByteBuffer.wrap(ciphertext).get(keyId);
        if (findKey(keyId) == encryptionKeys.get(0)) return ciphertext.clone();
        return encrypt(userId, decrypt(userId, ciphertext));
    }

    private EncryptionKey findKey(byte[] keyId) {
        return encryptionKeys.stream()
            .filter(key -> MessageDigest.isEqual(key.id(), keyId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("MFA encryption key version is unavailable"));
    }

    private record EncryptionKey(byte[] id, byte[] value) {}
}
