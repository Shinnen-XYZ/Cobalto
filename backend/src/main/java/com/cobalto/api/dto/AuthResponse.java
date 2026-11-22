package com.cobalto.api.dto;

public record AuthResponse(String accessToken, String refreshToken, String tokenType,
                           long expiresInSeconds, boolean mfaRequired, String mfaChallenge,
                           UserResponse user) {
    public static AuthResponse authenticated(String accessToken, String refreshToken,
                                             long expiresInSeconds, UserResponse user) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds,
            false, null, user);
    }

    public static AuthResponse mfaChallenge(String challenge) {
        return new AuthResponse(null, null, null, 0, true, challenge, null);
    }
}
