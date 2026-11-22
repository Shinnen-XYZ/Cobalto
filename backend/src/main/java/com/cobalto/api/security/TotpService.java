package com.cobalto.api.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class TotpService {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private final SecureRandom secureRandom = new SecureRandom();

    public String newSecret() {
        byte[] secret = new byte[20];
        secureRandom.nextBytes(secret);
        return encodeBase32(secret);
    }

    public OptionalLong matchingCounter(String secret, String suppliedCode, Instant now) {
        if (suppliedCode == null || !suppliedCode.matches("\\d{6}")) {
            return OptionalLong.empty();
        }

        byte[] secretBytes = decodeBase32(secret);
        long currentCounter = now.getEpochSecond() / TIME_STEP_SECONDS;
        byte[] suppliedBytes = suppliedCode.getBytes(StandardCharsets.US_ASCII);
        for (long counter = currentCounter - 1; counter <= currentCounter + 1; counter++) {
            byte[] generatedBytes = generateCode(secretBytes, counter)
                .getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(generatedBytes, suppliedBytes)) {
                return OptionalLong.of(counter);
            }
        }
        return OptionalLong.empty();
    }

    public String otpAuthUri(String email, String secret) {
        String label = URLEncoder.encode("Cobalto:" + email, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secret
            + "&issuer=Cobalto&algorithm=SHA1&digits=6&period=" + TIME_STEP_SECONDS;
    }

    static String encodeBase32(byte[] input) {
        StringBuilder output = new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsInBuffer = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5;
                output.append(BASE32[(buffer >>> bitsInBuffer) & 0x1f]);
            }
        }
        if (bitsInBuffer > 0) {
            output.append(BASE32[(buffer << (5 - bitsInBuffer)) & 0x1f]);
        }
        return output.toString();
    }

    static byte[] decodeBase32(String encoded) {
        byte[] output = new byte[encoded.length() * 5 / 8];
        int buffer = 0;
        int bitsInBuffer = 0;
        int outputIndex = 0;
        for (char character : encoded.toCharArray()) {
            int value = base32Value(character);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 secret");
            }
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8;
                output[outputIndex++] = (byte) ((buffer >>> bitsInBuffer) & 0xff);
            }
        }
        return output;
    }

    private static String generateCode(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
            int code = binary % 1_000_000;
            return String.format(java.util.Locale.ROOT, "%0" + CODE_DIGITS + "d", code);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", exception);
        }
    }

    private static int base32Value(char character) {
        if (character >= 'A' && character <= 'Z') return character - 'A';
        if (character >= '2' && character <= '7') return character - '2' + 26;
        return -1;
    }
}
