package com.cobalto.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_email_verification_outbox")
public class EmailVerificationOutbox {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_id", nullable = false, unique = true, updatable = false)
    private UUID tokenId;

    @Column(name = "recipient_email", nullable = false, updatable = false, length = 254)
    private String recipientEmail;

    @Column(name = "encrypted_token")
    private byte[] encryptedToken;

    @Column(name = "lease_token", insertable = false)
    private UUID leaseToken;

    @Column(name = "lease_until", insertable = false)
    private Instant leaseUntil;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at", insertable = false)
    private Instant sentAt;

    @Column(name = "last_error_code", insertable = false, length = 64)
    private String lastErrorCode;

    protected EmailVerificationOutbox() {}

    public EmailVerificationOutbox(
        UUID id, UUID userId, UUID tokenId, String recipientEmail, byte[] encryptedToken,
        Instant now
    ) {
        this.id = id;
        this.userId = userId;
        this.tokenId = tokenId;
        this.recipientEmail = recipientEmail;
        this.encryptedToken = encryptedToken;
        this.status = "PENDING";
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getTokenId() { return tokenId; }
    public String getRecipientEmail() { return recipientEmail; }
    public byte[] getEncryptedToken() { return encryptedToken; }
    public String getStatus() { return status; }
    public UUID getLeaseToken() { return leaseToken; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public short getAttempts() { return attempts; }

    public void claim(UUID leaseToken, Instant leaseUntil) {
        if (attempts >= 5) {
            throw new IllegalStateException("Email verification attempt limit was exceeded");
        }
        attempts++;
        this.status = "PROCESSING";
        this.leaseToken = leaseToken;
        this.leaseUntil = leaseUntil;
    }

    public void recoverExpiredLease(Instant nextAttemptAt) {
        this.leaseToken = null;
        this.leaseUntil = null;
        this.lastErrorCode = "LEASE_EXPIRED";
        if (attempts >= 5) {
            this.status = "FAILED";
            this.encryptedToken = null;
            return;
        }
        this.status = "PENDING";
        this.nextAttemptAt = nextAttemptAt;
    }

}
