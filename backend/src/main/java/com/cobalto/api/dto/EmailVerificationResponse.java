package com.cobalto.api.dto;

public record EmailVerificationResponse(boolean emailVerified, String message) {}
