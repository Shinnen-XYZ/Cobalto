package com.cobalto.api.repository;

import com.cobalto.api.domain.TransferReversalAudit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferReversalAuditRepository
    extends JpaRepository<TransferReversalAudit, UUID> {
    Optional<TransferReversalAudit> findByOriginalTransactionId(UUID originalTransactionId);
    Optional<TransferReversalAudit> findByReversalTransactionId(UUID reversalTransactionId);
    Optional<TransferReversalAudit> findByAuthorizedByUserIdAndIdempotencyKeyHash(
        UUID authorizedByUserId, String idempotencyKeyHash);
}
