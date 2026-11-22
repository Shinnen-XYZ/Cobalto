package com.cobalto.api.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String fullName, String email, String kycStatus,
                           Instant createdAt, boolean mfaEnabled,
                           boolean canReverseTransfers) {}
