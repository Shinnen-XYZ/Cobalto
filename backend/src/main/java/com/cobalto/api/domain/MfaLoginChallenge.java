package com.cobalto.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_mfa_login_challenges")
public class MfaLoginChallenge {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private short failedAttempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected MfaLoginChallenge() {}

    public MfaLoginChallenge(UUID id, UUID userId, String tokenHash,
                             Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void fail(Instant now) {
        failedAttempts++;
        if (failedAttempts >= 5) consumedAt = now;
    }

    public void consume(Instant now) {
        consumedAt = now;
    }

    public UUID getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public short getFailedAttempts() { return failedAttempts; }
    public Instant getConsumedAt() { return consumedAt; }
}
