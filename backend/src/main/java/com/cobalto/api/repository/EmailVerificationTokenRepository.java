package com.cobalto.api.repository;

import com.cobalto.api.domain.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from EmailVerificationToken t where t.id = :id")
    Optional<EmailVerificationToken> findLockedById(@Param("id") UUID id);

    @Modifying
    @Query(value = """
        update auth_email_verification_tokens
        set revoked_at = :now
        where user_id = :userId and consumed_at is null and revoked_at is null
        """, nativeQuery = true)
    int revokeActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
        update auth_email_verification_tokens
        set revoked_at = :now
        where id = :id and consumed_at is null and revoked_at is null
        """, nativeQuery = true)
    int revokeById(@Param("id") UUID id, @Param("now") Instant now);
}
