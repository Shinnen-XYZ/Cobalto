package com.cobalto.api.repository;

import com.cobalto.api.domain.RefreshSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.tokenHash = :tokenHash")
    Optional<RefreshSession> findLockedByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :now "
        + "where s.familyId = :familyId and s.revokedAt is null")
    int revokeActiveFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :now "
        + "where s.userId = :userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
