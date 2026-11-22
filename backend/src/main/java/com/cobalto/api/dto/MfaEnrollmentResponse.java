package com.cobalto.api.dto;

import java.util.List;

public record MfaEnrollmentResponse(boolean enabled, List<String> recoveryCodes, AuthResponse session) {}
