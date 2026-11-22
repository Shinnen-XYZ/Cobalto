package com.cobalto.api.repository;

import com.cobalto.api.domain.LedgerEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findAllByTransaction_IdOrderByIdAsc(UUID transactionId);

    @Query(value = """
        select coalesce(sum(case when e.entry_type = 'CREDIT' then e.amount else -e.amount end), 0)
        from ledger_entries e
        where e.account_id = :accountId
        """, nativeQuery = true)
    BigDecimal findBalance(@Param("accountId") UUID accountId);

    @Query(value = """
        select t.id as "transactionId", t.type as "type", t.description as "description",
               t.created_at as "createdAt",
               case when e.entry_type = 'CREDIT' then e.amount else -e.amount end as amount,
               case when t.type = 'TRANSFER' and exists (
                   select 1 from ledger_transactions r
                   where r.reversal_of_transaction_id = t.id
               ) then 'REVERSED' else 'COMPLETED' end as status
        from ledger_entries e
        join ledger_transactions t on t.id = e.transaction_id
        where e.account_id = :accountId
        order by t.created_at desc
        limit :limit offset :offset
        """, nativeQuery = true)
    List<MovementView> findMovements(
        @Param("accountId") UUID accountId,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    interface MovementView {
        UUID getTransactionId();
        String getType();
        String getDescription();
        java.time.Instant getCreatedAt();
        BigDecimal getAmount();
        String getStatus();
    }
}
