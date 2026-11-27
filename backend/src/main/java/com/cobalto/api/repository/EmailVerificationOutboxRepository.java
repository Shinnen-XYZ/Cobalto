package com.cobalto.api.repository;

import com.cobalto.api.domain.EmailVerificationOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationOutboxRepository
    extends JpaRepository<EmailVerificationOutbox, UUID> {
    @Modifying
    @Query("""
        update EmailVerificationOutbox o
        set o.status = 'CANCELLED', o.encryptedToken = null,
            o.leaseToken = null, o.leaseUntil = null
        where o.userId = :userId and o.status in ('PENDING', 'PROCESSING')
        """)
    int cancelPendingForUser(@Param("userId") UUID userId);

    @Query(value = """
        select * from auth_email_verification_outbox
        where ((status = 'PENDING' and next_attempt_at <= :now)
           or (status = 'PROCESSING' and lease_until <= :now))
          and attempts < :maxAttempts
        order by coalesce(lease_until, next_attempt_at), created_at
        limit :batchSize
        for update skip locked
        """, nativeQuery = true)
    List<EmailVerificationOutbox> findClaimableForUpdate(
        @Param("now") Instant now,
        @Param("batchSize") int batchSize,
        @Param("maxAttempts") int maxAttempts);

    @Query(value = """
        select * from auth_email_verification_outbox
        where attempts >= :maxAttempts
          and ((status = 'PENDING' and next_attempt_at <= :now)
            or (status = 'PROCESSING' and lease_until <= :now))
        order by coalesce(lease_until, next_attempt_at), created_at
        limit :batchSize
        """, nativeQuery = true)
    List<EmailVerificationOutbox> findExhaustedDue(
        @Param("now") Instant now,
        @Param("batchSize") int batchSize,
        @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = """
        update auth_email_verification_outbox
        set status = 'FAILED',
            encrypted_token = null,
            last_error_code = :errorCode,
            lease_token = null,
            lease_until = null
        where id = :id and attempts >= :maxAttempts
          and ((status = 'PENDING' and next_attempt_at <= :now)
            or (status = 'PROCESSING' and lease_until <= :now))
        """, nativeQuery = true)
    int failExhaustedDue(
        @Param("id") UUID id,
        @Param("now") Instant now,
        @Param("errorCode") String errorCode,
        @Param("maxAttempts") int maxAttempts);

    Optional<EmailVerificationOutbox> findByIdAndStatusAndLeaseToken(
        UUID id, String status, UUID leaseToken);

    @Modifying
    @Query(value = """
        update auth_email_verification_outbox o
        set lease_until = :newLeaseUntil
        where o.id = :id
          and o.status = 'PROCESSING'
          and o.lease_token = :leaseToken
          and o.lease_until > :now
          and o.user_id = :userId
          and o.token_id = :tokenId
          and o.recipient_email = :recipientEmail
          and o.encrypted_token = :encryptedToken
          and exists (
              select 1 from app_users u
              where u.id = o.user_id
                and u.email = o.recipient_email
                and u.email_verified = false
          )
          and exists (
              select 1 from auth_email_verification_tokens t
              where t.id = o.token_id
                and t.user_id = o.user_id
                and t.token_hash = :tokenHash
                and t.consumed_at is null
                and t.revoked_at is null
                and t.expires_at > :now
          )
        """, nativeQuery = true)
    int renewLeaseForSend(
        @Param("id") UUID id,
        @Param("leaseToken") UUID leaseToken,
        @Param("userId") UUID userId,
        @Param("tokenId") UUID tokenId,
        @Param("recipientEmail") String recipientEmail,
        @Param("tokenHash") String tokenHash,
        @Param("encryptedToken") byte[] encryptedToken,
        @Param("now") Instant now,
        @Param("newLeaseUntil") Instant newLeaseUntil);

    @Modifying
    @Query(value = """
        update auth_email_verification_outbox
        set status = 'SENT',
            sent_at = :now,
            encrypted_token = null,
            last_error_code = null,
            lease_token = null,
            lease_until = null
        where id = :id and status = 'PROCESSING' and lease_token = :leaseToken
        """, nativeQuery = true)
    int markSent(
        @Param("id") UUID id, @Param("leaseToken") UUID leaseToken, @Param("now") Instant now);

    @Modifying
    @Query(value = """
        update auth_email_verification_outbox
        set status = case when attempts >= :maxAttempts then 'FAILED' else 'PENDING' end,
            encrypted_token = case
                when attempts >= :maxAttempts then null else encrypted_token end,
            next_attempt_at = :nextAttemptAt,
            last_error_code = :errorCode,
            lease_token = null,
            lease_until = null
        where id = :id and status = 'PROCESSING' and lease_token = :leaseToken
        """, nativeQuery = true)
    int recordFailure(
        @Param("id") UUID id,
        @Param("leaseToken") UUID leaseToken,
        @Param("nextAttemptAt") Instant nextAttemptAt,
        @Param("errorCode") String errorCode,
        @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = """
        update auth_email_verification_outbox
        set status = 'CANCELLED',
            encrypted_token = null,
            lease_token = null,
            lease_until = null
        where id = :id and status = 'PROCESSING' and lease_token = :leaseToken
        """, nativeQuery = true)
    int cancelClaim(@Param("id") UUID id, @Param("leaseToken") UUID leaseToken);
}
