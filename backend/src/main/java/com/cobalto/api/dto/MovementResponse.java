package com.cobalto.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MovementResponse(UUID transactionId, String type, String description,
                               Instant createdAt, BigDecimal amount, String status) {}
