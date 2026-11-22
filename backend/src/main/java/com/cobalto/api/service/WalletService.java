package com.cobalto.api.service;

import com.cobalto.api.domain.AccountType;
import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.EntryType;
import com.cobalto.api.domain.LedgerAccount;
import com.cobalto.api.domain.LedgerEntry;
import com.cobalto.api.domain.LedgerTransaction;
import com.cobalto.api.dto.MovementResponse;
import com.cobalto.api.dto.RecipientConfirmationResponse;
import com.cobalto.api.dto.TransferReceiptResponse;
import com.cobalto.api.dto.TransferRequest;
import com.cobalto.api.dto.TransferResponse;
import com.cobalto.api.dto.WalletResponse;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.LedgerAccountRepository;
import com.cobalto.api.repository.LedgerEntryRepository;
import com.cobalto.api.repository.LedgerTransactionRepository;
import com.cobalto.api.repository.TransferReversalAuditRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!migrate")
public class WalletService {
    private static final String CURRENCY = AuthService.DEFAULT_CURRENCY;
    private static final BigDecimal NO_HELD_BALANCE = BigDecimal.ZERO.setScale(4);

    private final AppUserRepository userRepository;
    private final LedgerAccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final TransferReversalAuditRepository reversalAuditRepository;
    private final LedgerService ledgerService;
    private final NotificationService notificationService;
    private final TransferAuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;

    public WalletService(
        AppUserRepository userRepository,
        LedgerAccountRepository accountRepository,
        LedgerEntryRepository entryRepository,
        LedgerTransactionRepository transactionRepository,
        TransferReversalAuditRepository reversalAuditRepository,
        LedgerService ledgerService,
        NotificationService notificationService,
        TransferAuditService auditService,
        PasswordEncoder passwordEncoder,
        MfaService mfaService
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.transactionRepository = transactionRepository;
        this.reversalAuditRepository = reversalAuditRepository;
        this.ledgerService = ledgerService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID userId) {
        LedgerAccount account = findWallet(userId);
        BigDecimal balance = entryRepository.findBalance(account.getId());
        List<MovementResponse> movements = loadMovements(account.getId(), 20, 0);
        return new WalletResponse(CURRENCY, balance, balance, NO_HELD_BALANCE, movements);
    }

    @Transactional(readOnly = true)
    public List<MovementResponse> listMovements(UUID userId, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 1_000_000) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                "La paginación solicitada no es válida");
        }
        return loadMovements(findWallet(userId).getId(), limit, offset);
    }

    @Transactional(readOnly = true)
    public RecipientConfirmationResponse confirmRecipient(UUID senderId, String email) {
        String normalizedEmail = AuthService.normalizeEmail(email);
        AppUser recipient = userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND",
                "No se encontró la cuenta destinataria"));
        if (recipient.getId().equals(senderId)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER",
                "No puedes transferir a tu propia cuenta");
        }
        findWallet(recipient.getId());
        return new RecipientConfirmationResponse(
            recipient.getFullName(), maskEmail(recipient.getEmail()));
    }

    @Transactional
    public TransferResponse transfer(UUID senderId, UUID idempotencyKey, TransferRequest request) {
        validateAmount(request.amount());
        String recipientEmail = AuthService.normalizeEmail(request.recipientEmail());
        String fingerprint = fingerprint(recipientEmail, request.amount());
        auditService.recordAttempt(senderId, idempotencyKey);
        try {
            return performTransfer(senderId, idempotencyKey, request, recipientEmail, fingerprint);
        } catch (DomainException exception) {
            auditService.recordFailure(senderId, idempotencyKey, exception.getCode());
            throw exception;
        } catch (DataAccessException exception) {
            auditService.recordFailure(senderId, idempotencyKey, "DATABASE_ERROR");
            throw exception;
        } catch (RuntimeException exception) {
            auditService.recordFailure(senderId, idempotencyKey, "INTERNAL_ERROR");
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public TransferReceiptResponse getTransferDetails(UUID userId, UUID transactionId) {
        LedgerTransaction transaction = transactionRepository.findById(transactionId)
            .filter(candidate -> "TRANSFER".equals(candidate.getType())
                || "REVERSAL".equals(candidate.getType()))
            .orElseThrow(WalletService::transferNotFound);
        boolean isReversal = "REVERSAL".equals(transaction.getType());
        LedgerTransaction original = isReversal
            ? transactionRepository.findById(transaction.getReversalOfTransactionId())
                .filter(candidate -> "TRANSFER".equals(candidate.getType()))
                .orElseThrow(WalletService::transferNotFound)
            : transaction;
        List<LedgerEntry> originalEntries =
            entryRepository.findAllByTransaction_IdOrderByIdAsc(original.getId());
        if (originalEntries.size() != 2) {
            throw new IllegalStateException("La transferencia no tiene exactamente dos asientos");
        }
        LedgerEntry senderEntry = originalEntries.stream()
            .filter(entry -> entry.getAccount().getAccountType() == AccountType.WALLET
                && original.getActorUserId().equals(entry.getAccount().getOwnerUserId())
                && entry.getEntryType() == EntryType.DEBIT)
            .findFirst()
            .orElseThrow(WalletService::transferNotFound);
        LedgerEntry recipientEntry = originalEntries.stream()
            .filter(entry -> entry.getAccount().getAccountType() == AccountType.WALLET
                && !original.getActorUserId().equals(entry.getAccount().getOwnerUserId())
                && entry.getEntryType() == EntryType.CREDIT)
            .findFirst()
            .orElseThrow(WalletService::transferNotFound);
        if (senderEntry.getEntryType() != EntryType.DEBIT
            || recipientEntry.getEntryType() != EntryType.CREDIT
            || senderEntry.getAmount().compareTo(recipientEntry.getAmount()) != 0) {
            throw new IllegalStateException("Los asientos de la transferencia son inconsistentes");
        }

        UUID senderId = original.getActorUserId();
        UUID recipientId = recipientEntry.getAccount().getOwnerUserId();
        var reversalAudit = isReversal
            ? reversalAuditRepository.findByReversalTransactionId(transactionId)
                .orElseThrow(WalletService::transferNotFound)
            : reversalAuditRepository.findByOriginalTransactionId(original.getId()).orElse(null);
        if (isReversal && (!transactionId.equals(reversalAudit.getReversalTransactionId())
            || !original.getId().equals(reversalAudit.getOriginalTransactionId())
            || !java.util.Objects.equals(transaction.getReversalAuthorizedByUserId(),
                reversalAudit.getAuthorizedByUserId()))) {
            throw new IllegalStateException("La auditoría de reversión no coincide con el ledger");
        }
        boolean isParty = userId.equals(senderId) || userId.equals(recipientId);
        boolean isAuthorizer = reversalAudit != null
            && userId.equals(reversalAudit.getAuthorizedByUserId());
        if (!isParty && !isAuthorizer) {
            throw transferNotFound();
        }
        String direction;
        if (!isParty) {
            direction = "AUTHORIZED";
        } else if (!isReversal) {
            direction = userId.equals(senderId) ? "SENT" : "RECEIVED";
        } else {
            LedgerEntry viewerEntry = entryRepository
                .findAllByTransaction_IdOrderByIdAsc(transactionId).stream()
                .filter(entry -> userId.equals(entry.getAccount().getOwnerUserId()))
                .findFirst()
                .orElseThrow(WalletService::transferNotFound);
            direction = viewerEntry.getEntryType() == EntryType.CREDIT
                ? "REVERSAL_CREDIT" : "REVERSAL_DEBIT";
        }
        UUID counterpartyId = isParty ? (userId.equals(senderId) ? recipientId : senderId) : null;
        AppUser counterparty = counterpartyId == null ? null : userRepository.findById(counterpartyId)
            .orElseThrow(WalletService::transferNotFound);
        String status = isReversal ? "COMPLETED"
            : reversalAudit == null ? "COMPLETED" : "REVERSED";
        return receipt(transaction, direction, senderEntry.getAmount(), counterparty, status,
            isReversal ? reversalAudit.getOriginalTransactionId() : null,
            isReversal ? reversalAudit.getReason() : null);
    }

    private TransferResponse performTransfer(
        UUID senderId,
        UUID idempotencyKey,
        TransferRequest request,
        String recipientEmail,
        String requestFingerprint
    ) {
        LedgerTransaction prior = transactionRepository
            .findByActorUserIdAndIdempotencyKey(senderId, idempotencyKey.toString()).orElse(null);
        if (prior != null) {
            if (!"TRANSFER".equals(prior.getType())
                || !requestFingerprint.equals(prior.getRequestFingerprint())) {
                throw idempotencyKeyReused();
            }
            auditService.recordReplay(senderId, idempotencyKey, prior.getId());
            return transferResponse(prior, request.amount());
        }

        AppUser recipient = userRepository.findByEmail(recipientEmail)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND",
                "No se encontró la cuenta destinataria"));
        if (recipient.getId().equals(senderId)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER",
                "No puedes transferir a tu propia cuenta");
        }

        LedgerAccount senderWallet = findWallet(senderId);
        LedgerAccount recipientWallet = findWallet(recipient.getId());
        Map<UUID, LedgerAccount> lockedAccounts = List.of(senderWallet.getId(), recipientWallet.getId())
            .stream().sorted(Comparator.naturalOrder())
            .map(id -> accountRepository.findLockedById(id)
                .orElseThrow(() -> new IllegalStateException("No se encontró una cuenta bloqueada")))
            .collect(Collectors.toMap(LedgerAccount::getId, account -> account));
        senderWallet = lockedAccounts.get(senderWallet.getId());
        recipientWallet = lockedAccounts.get(recipientWallet.getId());

        prior = transactionRepository
            .findByActorUserIdAndIdempotencyKey(senderId, idempotencyKey.toString()).orElse(null);
        if (prior != null) {
            if (!"TRANSFER".equals(prior.getType())
                || !requestFingerprint.equals(prior.getRequestFingerprint())) {
                throw idempotencyKeyReused();
            }
            auditService.recordReplay(senderId, idempotencyKey, prior.getId());
            return transferResponse(prior, request.amount());
        }

        BigDecimal balance = entryRepository.findBalance(senderWallet.getId());
        if (balance.compareTo(request.amount()) < 0) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Saldo insuficiente");
        }

        AppUser sender = userRepository.findById(senderId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        if (!passwordEncoder.matches(request.password(), sender.getPasswordHash())) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "La contraseña no es válida");
        }
        if (sender.isMfaEnabled()) {
            mfaService.verifySensitiveAction(senderId, request.mfaFactor());
        } else if (request.mfaFactor() != null && !request.mfaFactor().isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "MFA_NOT_ENABLED",
                "Esta cuenta no tiene MFA activado");
        }

        LedgerTransaction transaction = ledgerService.post(
            "TRANSFER", senderId, idempotencyKey.toString(), requestFingerprint,
            CURRENCY, "Transferencia entre usuarios",
            senderWallet, recipientWallet, request.amount());
        notificationService.notifyTransfer(
            senderId, recipient.getId(), transaction.getId(),
            sender.getFullName(), recipient.getFullName(), CURRENCY, request.amount(),
            transaction.getCreatedAt());
        auditService.recordCompleted(senderId, idempotencyKey, transaction.getId());
        return transferResponse(transaction, request.amount());
    }

    private List<MovementResponse> loadMovements(UUID accountId, int limit, int offset) {
        return entryRepository.findMovements(accountId, limit, offset).stream()
            .map(movement -> new MovementResponse(
                movement.getTransactionId(),
                movement.getType(),
                movement.getDescription(),
                movement.getCreatedAt(),
                movement.getAmount(),
                movement.getStatus()))
            .toList();
    }

    private TransferResponse transferResponse(LedgerTransaction transaction, BigDecimal amount) {
        return new TransferResponse(transaction.getId(), "COMPLETED", transaction.getCurrency(),
            amount, transaction.getCreatedAt());
    }

    private TransferReceiptResponse receipt(
        LedgerTransaction transaction, String direction, BigDecimal amount, AppUser counterparty,
        String status, UUID originalTransactionId, String reason
    ) {
        String prefix = "REVERSAL".equals(transaction.getType()) ? "CB-RV-" : "CB-";
        String receiptNumber = prefix
            + transaction.getId().toString().toUpperCase(java.util.Locale.ROOT);
        return new TransferReceiptResponse(
            transaction.getId(),
            receiptNumber,
            status,
            direction,
            transaction.getCurrency(),
            amount,
            transaction.getCreatedAt(),
            transaction.getDescription(),
            counterparty == null ? "Operación autorizada" : counterparty.getFullName(),
            counterparty == null ? "***" : maskEmail(counterparty.getEmail()),
            transaction.getType(),
            originalTransactionId,
            reason);
    }

    private LedgerAccount findWallet(UUID userId) {
        return accountRepository.findByOwnerUserIdAndCurrency(userId, CURRENCY)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND",
                "No se encontró la billetera"));
    }

    private String fingerprint(String email, BigDecimal amount) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((email + "|" + amount.stripTrailingZeros().toPlainString() + "|" + CURRENCY)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0
            || amount.stripTrailingZeros().scale() > 2
            || amount.precision() - amount.scale() > 15) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                "El monto debe ser positivo, tener hasta dos decimales y no exceder GTQ 999999999999999.99");
        }
    }

    private static String maskEmail(String email) {
        int separator = email.indexOf('@');
        if (separator <= 0) return "***";
        return email.substring(0, 1) + "***" + email.substring(separator);
    }

    private static DomainException idempotencyKeyReused() {
        return new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
            "La clave de idempotencia ya se usó con otros datos");
    }

    private static DomainException transferNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
            "No se encontró la transferencia");
    }
}
