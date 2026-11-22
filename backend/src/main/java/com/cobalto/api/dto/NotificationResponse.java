package com.cobalto.api.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    UUID transactionId,
    String type,
    String message,
    Instant createdAt,
    Instant readAt
) {}
