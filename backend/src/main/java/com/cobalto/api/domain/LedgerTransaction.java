package com.cobalto.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions", uniqueConstraints = {
    @UniqueConstraint(name = "uq_ledger_idempotency", columnNames = {"actor_user_id", "idempotency_key"})
})
public class LedgerTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(name = "reversal_of_transaction_id", updatable = false)
    private UUID reversalOfTransactionId;

    @Column(name = "reversal_authorized_by_user_id", updatable = false)
    private UUID reversalAuthorizedByUserId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 160)
    private String description;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {}

    public LedgerTransaction(String type, UUID actorUserId, String idempotencyKey,
                             String requestFingerprint, String currency, String description) {
        this(type, actorUserId, idempotencyKey, requestFingerprint, currency, description, null);
    }

    public LedgerTransaction(String type, UUID actorUserId, String idempotencyKey,
                             String requestFingerprint, String currency, String description,
                             UUID reversalOfTransactionId) {
        this(type, actorUserId, idempotencyKey, requestFingerprint, currency, description,
            reversalOfTransactionId, null);
    }

    public LedgerTransaction(String type, UUID actorUserId, String idempotencyKey,
                             String requestFingerprint, String currency, String description,
                             UUID reversalOfTransactionId, UUID reversalAuthorizedByUserId) {
        this.type = type;
        this.actorUserId = actorUserId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.currency = currency;
        this.description = description;
        this.reversalOfTransactionId = reversalOfTransactionId;
        this.reversalAuthorizedByUserId = reversalAuthorizedByUserId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public UUID getActorUserId() { return actorUserId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getReversalOfTransactionId() { return reversalOfTransactionId; }
    public UUID getReversalAuthorizedByUserId() { return reversalAuthorizedByUserId; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
