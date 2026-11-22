package com.cobalto.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_mfa_recovery_codes", uniqueConstraints = {
    @UniqueConstraint(name = "uq_auth_mfa_recovery_user_hash", columnNames = {"user_id", "code_hash"})
})
public class MfaRecoveryCode {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected MfaRecoveryCode() {}

    public MfaRecoveryCode(UUID id, UUID userId, String codeHash, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
    }

    public void consume(Instant now) {
        this.usedAt = now;
    }

    public Instant getUsedAt() { return usedAt; }
}
