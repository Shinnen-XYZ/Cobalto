package com.cobalto.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_reversal_audits")
public class TransferReversalAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "original_transaction_id", nullable = false, updatable = false, unique = true)
    private UUID originalTransactionId;

    @Column(name = "reversal_transaction_id", nullable = false, updatable = false, unique = true)
    private UUID reversalTransactionId;

    @Column(name = "authorized_by_user_id", nullable = false, updatable = false)
    private UUID authorizedByUserId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferReversalAudit() {}

    public TransferReversalAudit(UUID originalTransactionId, UUID reversalTransactionId,
                                 UUID authorizedByUserId, String idempotencyKeyHash,
                                 String requestFingerprint, String reason, Instant createdAt) {
        this.originalTransactionId = originalTransactionId;
        this.reversalTransactionId = reversalTransactionId;
        this.authorizedByUserId = authorizedByUserId;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.requestFingerprint = requestFingerprint;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOriginalTransactionId() { return originalTransactionId; }
    public UUID getReversalTransactionId() { return reversalTransactionId; }
    public UUID getAuthorizedByUserId() { return authorizedByUserId; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
