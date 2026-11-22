package com.cobalto.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferReceiptResponse(
    UUID transactionId,
    String receiptNumber,
    String status,
    String direction,
    String currency,
    BigDecimal amount,
    Instant createdAt,
    String description,
    String counterpartyName,
    String maskedCounterpartyEmail,
    String operationType,
    UUID originalTransactionId,
    String reason
) {}
