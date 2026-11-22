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
@Table(name = "app_users")
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String fullName;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "mfa_secret_encrypted")
    private byte[] mfaSecretEncrypted;

    @Column(name = "mfa_pending_secret_encrypted")
    private byte[] mfaPendingSecretEncrypted;

    @Column(name = "mfa_pending_expires_at")
    private Instant mfaPendingExpiresAt;

    @Column(name = "mfa_last_counter", nullable = false)
    private long mfaLastCounter = -1;

    @Column(name = "mfa_failed_attempts", nullable = false)
    private short mfaFailedAttempts;

    @Column(name = "mfa_locked_until")
    private Instant mfaLockedUntil;

    protected AppUser() {}

    public AppUser(String fullName, String email, String passwordHash) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public byte[] getMfaSecretEncrypted() { return mfaSecretEncrypted; }
    public byte[] getMfaPendingSecretEncrypted() { return mfaPendingSecretEncrypted; }
    public Instant getMfaPendingExpiresAt() { return mfaPendingExpiresAt; }
    public long getMfaLastCounter() { return mfaLastCounter; }
    public short getMfaFailedAttempts() { return mfaFailedAttempts; }
    public Instant getMfaLockedUntil() { return mfaLockedUntil; }

    public void completeMfaEnrollment(byte[] encryptedSecret, long lastCounter) {
        this.mfaSecretEncrypted = encryptedSecret;
        this.mfaPendingSecretEncrypted = null;
        this.mfaPendingExpiresAt = null;
        this.mfaLastCounter = lastCounter;
        this.mfaEnabled = true;
    }

    public void setPendingMfaSecret(byte[] encryptedSecret, Instant expiresAt) {
        this.mfaPendingSecretEncrypted = encryptedSecret;
        this.mfaPendingExpiresAt = expiresAt;
    }

    public void updateMfaCounter(long counter) {
        this.mfaLastCounter = counter;
    }

    public void recordMfaFailure(Instant now) {
        if (mfaFailedAttempts < 5) mfaFailedAttempts++;
        if (mfaFailedAttempts >= 5) mfaLockedUntil = now.plusSeconds(900);
    }

    public void clearMfaFailures() {
        mfaFailedAttempts = 0;
        mfaLockedUntil = null;
    }

    public boolean isMfaLocked(Instant now) {
        return mfaLockedUntil != null && mfaLockedUntil.isAfter(now);
    }

    public void clearExpiredMfaLock(Instant now) {
        if (mfaLockedUntil != null && !mfaLockedUntil.isAfter(now)) {
            clearMfaFailures();
        }
    }

    public void disableMfa() {
        this.mfaEnabled = false;
        this.mfaSecretEncrypted = null;
        this.mfaPendingSecretEncrypted = null;
        this.mfaPendingExpiresAt = null;
        this.mfaLastCounter = -1;
        clearMfaFailures();
    }
}
