package com.cobalto.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_audit_events")
public class TransferAuditEvent {
    @Id
    private UUID id;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "event_type", nullable = false, length = 16, updatable = false)
    private String eventType;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "failure_code", length = 64, updatable = false)
    private String failureCode;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferAuditEvent() {}

    public TransferAuditEvent(UUID actorUserId, String idempotencyKeyHash, String eventType,
                              UUID transactionId, String failureCode, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.actorUserId = actorUserId;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.eventType = eventType;
        this.transactionId = transactionId;
        this.failureCode = failureCode;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getActorUserId() { return actorUserId; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public String getEventType() { return eventType; }
    public UUID getTransactionId() { return transactionId; }
    public String getFailureCode() { return failureCode; }
    public Instant getCreatedAt() { return createdAt; }
}
