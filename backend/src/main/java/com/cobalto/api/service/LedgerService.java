package com.cobalto.api.service;

import com.cobalto.api.domain.AccountType;
import com.cobalto.api.domain.EntryType;
import com.cobalto.api.domain.LedgerAccount;
import com.cobalto.api.domain.LedgerEntry;
import com.cobalto.api.domain.LedgerTransaction;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.LedgerAccountRepository;
import com.cobalto.api.repository.LedgerEntryRepository;
import com.cobalto.api.repository.LedgerTransactionRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerAccountRepository accountRepository;
    private final JdbcTemplate jdbcTemplate;

    public LedgerService(LedgerTransactionRepository transactionRepository,
                         LedgerEntryRepository entryRepository,
                         LedgerAccountRepository accountRepository,
                         JdbcTemplate jdbcTemplate) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(String type, UUID actorUserId, String idempotencyKey,
                                  String requestFingerprint, String currency, String description,
                                  LedgerAccount debitAccount, LedgerAccount creditAccount,
                                  BigDecimal amount) {
        UUID transactionId;
        if ("SANDBOX_CREDIT".equals(type)) {
            if (!"GTQ".equals(currency) || amount.compareTo(new BigDecimal("100.00")) != 0
                || !debitAccount.getId().equals(UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"))
                || !actorUserId.equals(creditAccount.getOwnerUserId())) {
                throw new IllegalArgumentException("Invalid initial sandbox credit");
            }
            transactionId = jdbcTemplate.queryForObject(
                "SELECT post_sandbox_initial_credit(?, ?)", UUID.class,
                actorUserId, creditAccount.getId());
        } else if ("TRANSFER".equals(type)) {
            transactionId = jdbcTemplate.queryForObject(
                "SELECT post_wallet_transfer(?, ?, ?, ?, ?, ?)", UUID.class,
                actorUserId, idempotencyKey, requestFingerprint,
                debitAccount.getId(), creditAccount.getId(), amount);
        } else {
            throw new IllegalArgumentException("Unsupported ledger transaction type");
        }
        return transactionRepository.findById(transactionId)
            .orElseThrow(() -> new IllegalStateException(
                "The database posting function returned a missing transaction"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction reverse(
        UUID originalTransactionId,
        UUID actorUserId,
        UUID authorizedByUserId,
        String idempotencyKey,
        String requestFingerprint
    ) {
        LedgerTransaction original = transactionRepository.findLockedById(originalTransactionId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND,
                "LEDGER_TRANSACTION_NOT_FOUND", "No se encontró la transacción contable"));
        if ("REVERSAL".equals(original.getType())
            || original.getReversalOfTransactionId() != null) {
            throw new DomainException(HttpStatus.CONFLICT, "TRANSACTION_NOT_REVERSIBLE",
                "La transacción no admite otra reversión");
        }
        if (!"TRANSFER".equals(original.getType())) {
            throw new DomainException(HttpStatus.CONFLICT, "TRANSFER_NOT_COMPLETED",
                "Solo se pueden revertir transferencias completadas");
        }
        if (!original.getActorUserId().equals(actorUserId)) {
            throw new DomainException(HttpStatus.CONFLICT, "TRANSACTION_NOT_REVERSIBLE",
                "La reversión no corresponde al actor contable original");
        }

        LedgerTransaction previous = transactionRepository
            .findByActorUserIdAndIdempotencyKey(actorUserId, idempotencyKey).orElse(null);
        if (previous != null) {
            if (originalTransactionId.equals(previous.getReversalOfTransactionId())
                && Objects.equals(requestFingerprint, previous.getRequestFingerprint())) {
                return previous;
            }
            throw new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "La clave de idempotencia ya se usó con otros datos");
        }
        if (transactionRepository.findByReversalOfTransactionId(originalTransactionId).isPresent()) {
            throw new DomainException(HttpStatus.CONFLICT, "TRANSACTION_ALREADY_REVERSED",
                "La transacción ya fue revertida");
        }

        List<LedgerEntry> originalEntries =
            entryRepository.findAllByTransaction_IdOrderByIdAsc(originalTransactionId);
        if (originalEntries.size() != 2
            || originalEntries.stream().filter(entry -> entry.getEntryType() == EntryType.DEBIT)
                .count() != 1
            || originalEntries.stream().filter(entry -> entry.getEntryType() == EntryType.CREDIT)
                .count() != 1
            || originalEntries.get(0).getAmount()
                .compareTo(originalEntries.get(1).getAmount()) != 0
            || originalEntries.stream().anyMatch(entry ->
                entry.getAccount().getAccountType() != AccountType.WALLET)) {
            throw new DomainException(HttpStatus.CONFLICT, "TRANSACTION_NOT_REVERSIBLE",
                "La transferencia no tiene asientos válidos para revertir");
        }

        Map<UUID, LedgerAccount> lockedAccounts = originalEntries.stream()
            .map(entry -> entry.getAccount().getId())
            .distinct()
            .sorted(Comparator.naturalOrder())
            .map(id -> accountRepository.findLockedById(id)
                .orElseThrow(() -> new IllegalStateException("Falta una cuenta del ledger")))
            .collect(Collectors.toMap(LedgerAccount::getId, account -> account));
        Map<UUID, BigDecimal> walletDeltas = originalEntries.stream()
            .filter(entry -> lockedAccounts.get(entry.getAccount().getId()).getAccountType()
                == AccountType.WALLET)
            .collect(Collectors.toMap(
                entry -> entry.getAccount().getId(),
                entry -> entry.getEntryType() == EntryType.DEBIT
                    ? entry.getAmount() : entry.getAmount().negate(),
                BigDecimal::add));
        for (Map.Entry<UUID, BigDecimal> delta : walletDeltas.entrySet()) {
            BigDecimal balance = entryRepository.findBalance(delta.getKey());
            if (balance.add(delta.getValue()).signum() < 0) {
                throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INSUFFICIENT_FUNDS_FOR_REVERSAL",
                    "La cuenta no tiene fondos disponibles para revertir la transacción");
            }
        }

        UUID reversalId = jdbcTemplate.queryForObject(
            "SELECT post_wallet_transfer_reversal(?, ?, ?, ?)", UUID.class,
            originalTransactionId, authorizedByUserId, idempotencyKey, requestFingerprint);
        return transactionRepository.findById(reversalId)
            .orElseThrow(() -> new IllegalStateException(
                "The database posting function returned a missing reversal"));
    }
}
