package com.cobalto.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpServiceTest {
    private final TotpService totpService = new TotpService();

    @Test
    void matchesRfc6238SixDigitVector() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        assertEquals(1L, totpService.matchingCounter(
            secret, "287082", Instant.ofEpochSecond(59)).orElseThrow());
    }

    @Test
    void rejectsMalformedAndIncorrectCodes() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        assertTrue(totpService.matchingCounter(secret, "12345", Instant.ofEpochSecond(59)).isEmpty());
        assertTrue(totpService.matchingCounter(secret, "000000", Instant.ofEpochSecond(59)).isEmpty());
    }

    @Test
    void base32RoundTripsRandomSecret() {
        byte[] secret = new byte[20];
        new java.security.SecureRandom().nextBytes(secret);

        assertArrayEquals(secret, TotpService.decodeBase32(TotpService.encodeBase32(secret)));
    }
}
