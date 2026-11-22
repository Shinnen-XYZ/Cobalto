package com.cobalto.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MfaSecretCipherTest {
    private final MfaSecretCipher cipher =
        new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsWithFreshNonceAndBindsCiphertextToUser() {
        byte[] first = cipher.encrypt("user-1", "secret-value");
        byte[] second = cipher.encrypt("user-1", "secret-value");

        assertNotEquals(Arrays.toString(first), Arrays.toString(second));
        assertEquals("secret-value", cipher.decrypt("user-1", first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("user-2", first));
    }

    @Test
    void rejectsTamperedCiphertext() {
        byte[] encrypted = cipher.encrypt("user-1", "secret-value");
        encrypted[encrypted.length - 1] ^= 1;

        assertThrows(IllegalStateException.class, () -> cipher.decrypt("user-1", encrypted));
    }

    @Test
    void keyRingCanDecryptExistingSecretsWhileEncryptingWithNewKey() {
        byte[] replacementRootKey = new byte[32];
        replacementRootKey[0] = 1;
        String replacementKey = Base64.getEncoder().encodeToString(replacementRootKey);
        String previousKey = Base64.getEncoder().encodeToString(new byte[32]);
        MfaSecretCipher previousCipher = new MfaSecretCipher(previousKey);
        MfaSecretCipher rotatedCipher = new MfaSecretCipher(replacementKey + "," + previousKey);
        byte[] oldCiphertext = previousCipher.encrypt("user-1", "old-secret");
        byte[] newCiphertext = rotatedCipher.encrypt("user-1", "new-secret");

        assertEquals("old-secret", rotatedCipher.decrypt("user-1", oldCiphertext));
        assertEquals("new-secret", rotatedCipher.decrypt("user-1", newCiphertext));
        assertThrows(IllegalStateException.class,
            () -> previousCipher.decrypt("user-1", newCiphertext));

        byte[] reencrypted = rotatedCipher.reencrypt("user-1", oldCiphertext);
        assertEquals("old-secret", new MfaSecretCipher(replacementKey)
            .decrypt("user-1", reencrypted));
    }

    @Test
    void requiresAnIndependentValidKeyRing() {
        assertThrows(IllegalArgumentException.class, () -> new MfaSecretCipher(""));
        assertThrows(IllegalArgumentException.class,
            () -> new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[31])));
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        assertThrows(IllegalArgumentException.class, () -> new MfaSecretCipher(key + ","));
        assertThrows(IllegalArgumentException.class, () -> new MfaSecretCipher(key + "," + key));
    }
}
