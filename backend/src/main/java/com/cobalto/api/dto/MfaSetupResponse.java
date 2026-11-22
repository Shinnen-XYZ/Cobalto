package com.cobalto.api.dto;

import java.time.Instant;

public record MfaSetupResponse(String secret, String otpAuthUri, Instant expiresAt) {}
