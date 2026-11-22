package com.cobalto.api.repository;

import com.cobalto.api.domain.MfaRecoveryCode;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
        "select c from MfaRecoveryCode c where c.userId = :userId and c.codeHash = :codeHash")
    Optional<MfaRecoveryCode> findLockedByUserIdAndCodeHash(
        @Param("userId") UUID userId, @Param("codeHash") String codeHash);

    List<MfaRecoveryCode> findAllByUserId(UUID userId);

    void deleteAllByUserId(UUID userId);
}
