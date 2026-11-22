package com.cobalto.api.repository;

import com.cobalto.api.domain.LedgerAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    Optional<LedgerAccount> findByOwnerUserIdAndCurrency(UUID ownerUserId, String currency);
    Optional<LedgerAccount> findByAccountTypeAndCurrency(
        com.cobalto.api.domain.AccountType accountType, String currency);

    @Query(value = """
        WITH locked AS MATERIALIZED (
            SELECT a.id, pg_advisory_xact_lock(hashtextextended(a.id::text, 0)) AS lock_result
            FROM ledger_accounts a
            WHERE a.id = :id
        )
        SELECT a.*
        FROM ledger_accounts a
        JOIN locked l ON l.id = a.id
        """, nativeQuery = true)
    Optional<LedgerAccount> findLockedById(@Param("id") UUID id);
}
