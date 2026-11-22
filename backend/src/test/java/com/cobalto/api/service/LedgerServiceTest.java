package com.cobalto.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobalto.api.domain.AccountType;
import com.cobalto.api.domain.EntryType;
import com.cobalto.api.domain.LedgerAccount;
import com.cobalto.api.domain.LedgerEntry;
import com.cobalto.api.domain.LedgerTransaction;
import com.cobalto.api.repository.LedgerAccountRepository;
import com.cobalto.api.repository.LedgerEntryRepository;
import com.cobalto.api.repository.LedgerTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {
    @Mock private LedgerTransactionRepository transactionRepository;
    @Mock private LedgerEntryRepository entryRepository;
    @Mock private LedgerAccountRepository accountRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private LedgerService ledgerService;

    @Test
    void initialSandboxCreditUsesTheRestrictedDatabasePostingFunction() {
        UUID actorId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        LedgerAccount issuer = new LedgerAccount(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            AccountType.SYSTEM, null, "GTQ");
        LedgerAccount wallet = new LedgerAccount(walletId, AccountType.WALLET, actorId, "GTQ");
        LedgerTransaction transaction = org.mockito.Mockito.mock(LedgerTransaction.class);
        when(jdbcTemplate.queryForObject(
            "SELECT post_sandbox_initial_credit(?, ?)", UUID.class, actorId, walletId))
            .thenReturn(transactionId);
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));

        assertEquals(transaction, ledgerService.post(
            "SANDBOX_CREDIT", actorId, "signup-" + actorId, null, "GTQ",
            "Saldo inicial de prueba", issuer, wallet, new BigDecimal("100.00")));

        verify(transactionRepository, never()).save(any(LedgerTransaction.class));
        verify(entryRepository, never()).saveAll(any());
    }

    @Test
    void transferUsesTheRestrictedDatabasePostingFunction() {
        UUID actorId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        LedgerAccount sender = new LedgerAccount(
            UUID.randomUUID(), AccountType.WALLET, actorId, "GTQ");
        LedgerAccount recipient = new LedgerAccount(
            UUID.randomUUID(), AccountType.WALLET, UUID.randomUUID(), "GTQ");
        LedgerTransaction transaction = org.mockito.Mockito.mock(LedgerTransaction.class);
        when(jdbcTemplate.queryForObject(
            "SELECT post_wallet_transfer(?, ?, ?, ?, ?, ?)", UUID.class,
            actorId, "key", "a".repeat(64), sender.getId(), recipient.getId(),
            new BigDecimal("15.25"))).thenReturn(transactionId);
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));

        assertEquals(transaction, ledgerService.post(
            "TRANSFER", actorId, "key", "a".repeat(64), "GTQ", "Transferencia",
            sender, recipient, new BigDecimal("15.25")));

        verify(transactionRepository, never()).save(any(LedgerTransaction.class));
        verify(entryRepository, never()).saveAll(any());
    }

    @Test
    void reversalDelegatesOppositeEntryCreationToTheDatabaseFunction() {
        UUID actorId = UUID.randomUUID();
        UUID authorizerId = UUID.randomUUID();
        UUID originalId = UUID.randomUUID();
        UUID reversalId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("8.00");
        LedgerAccount sender = new LedgerAccount(
            UUID.randomUUID(), AccountType.WALLET, actorId, "GTQ");
        LedgerAccount recipient = new LedgerAccount(
            UUID.randomUUID(), AccountType.WALLET, UUID.randomUUID(), "GTQ");
        LedgerTransaction original = org.mockito.Mockito.mock(LedgerTransaction.class);
        LedgerTransaction reversal = org.mockito.Mockito.mock(LedgerTransaction.class);
        when(original.getActorUserId()).thenReturn(actorId);
        when(original.getType()).thenReturn("TRANSFER");
        when(original.getReversalOfTransactionId()).thenReturn(null);
        when(transactionRepository.findLockedById(originalId)).thenReturn(Optional.of(original));
        when(transactionRepository.findByActorUserIdAndIdempotencyKey(actorId, "reversal-key"))
            .thenReturn(Optional.empty());
        when(transactionRepository.findByReversalOfTransactionId(originalId))
            .thenReturn(Optional.empty());
        when(entryRepository.findAllByTransaction_IdOrderByIdAsc(originalId)).thenReturn(List.of(
            new LedgerEntry(original, sender, EntryType.DEBIT, amount),
            new LedgerEntry(original, recipient, EntryType.CREDIT, amount)));
        when(accountRepository.findLockedById(sender.getId())).thenReturn(Optional.of(sender));
        when(accountRepository.findLockedById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(entryRepository.findBalance(sender.getId())).thenReturn(new BigDecimal("20.00"));
        when(entryRepository.findBalance(recipient.getId())).thenReturn(new BigDecimal("20.00"));
        when(jdbcTemplate.queryForObject(
            "SELECT post_wallet_transfer_reversal(?, ?, ?, ?)", UUID.class,
            originalId, authorizerId, "reversal-key", "b".repeat(64))).thenReturn(reversalId);
        when(transactionRepository.findById(reversalId)).thenReturn(Optional.of(reversal));

        assertEquals(reversal, ledgerService.reverse(
            originalId, actorId, authorizerId, "reversal-key", "b".repeat(64)));

        verify(transactionRepository, never()).save(any(LedgerTransaction.class));
        verify(entryRepository, never()).saveAll(any());
    }
}
