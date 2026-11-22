package com.cobalto.api.repository;

import com.cobalto.api.domain.LedgerTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    Optional<LedgerTransaction> findByActorUserIdAndIdempotencyKey(UUID actorUserId, String idempotencyKey);

    Optional<LedgerTransaction> findByReversalOfTransactionId(UUID originalTransactionId);

    @Query(value = """
        WITH locked AS MATERIALIZED (
            SELECT t.id,
                   pg_advisory_xact_lock(
                       hashtextextended('ledger-transaction:' || t.id::text, 0)
                   ) AS lock_result
            FROM ledger_transactions t
            WHERE t.id = :id
        )
        SELECT t.*
        FROM ledger_transactions t
        JOIN locked l ON l.id = t.id
        """, nativeQuery = true)
    Optional<LedgerTransaction> findLockedById(@Param("id") UUID id);
}
