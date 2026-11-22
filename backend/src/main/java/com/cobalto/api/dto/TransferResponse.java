package com.cobalto.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(UUID transactionId, String status, String currency,
                               BigDecimal amount, Instant createdAt) {}
