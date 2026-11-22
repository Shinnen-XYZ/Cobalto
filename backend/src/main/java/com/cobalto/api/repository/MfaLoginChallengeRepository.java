package com.cobalto.api.repository;

import com.cobalto.api.domain.MfaLoginChallenge;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaLoginChallengeRepository extends JpaRepository<MfaLoginChallenge, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MfaLoginChallenge c where c.tokenHash = :tokenHash")
    Optional<MfaLoginChallenge> findLockedByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("update MfaLoginChallenge c set c.consumedAt = :now "
        + "where c.userId = :userId and c.consumedAt is null")
    int consumePendingForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
