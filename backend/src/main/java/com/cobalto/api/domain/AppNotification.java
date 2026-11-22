package com.cobalto.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_notifications")
public class AppNotification {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "notification_type", nullable = false, length = 32, updatable = false)
    private String notificationType;

    @Column(nullable = false, length = 200, updatable = false)
    private String message;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected AppNotification() {}

    public AppNotification(UUID userId, UUID transactionId, String notificationType,
                           String message, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.transactionId = transactionId;
        this.notificationType = notificationType;
        this.message = message;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getTransactionId() { return transactionId; }
    public String getNotificationType() { return notificationType; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }
}
