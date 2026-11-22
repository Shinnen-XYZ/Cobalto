package com.cobalto.api.service;

import com.cobalto.api.domain.AccountType;
import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.EntryType;
import com.cobalto.api.domain.LedgerEntry;
import com.cobalto.api.domain.LedgerTransaction;
import com.cobalto.api.domain.TransferReversalAudit;
import com.cobalto.api.dto.ReversalRequest;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.LedgerEntryRepository;
import com.cobalto.api.repository.LedgerTransactionRepository;
import com.cobalto.api.repository.TransferReversalAuditRepository;
import com.cobalto.api.security.TokenHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!migrate")
public class TransferReversalService {
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AppUserRepository userRepository;
    private final TransferReversalAuditRepository auditRepository;
    private final UserPermissionService permissionService;
    private final LedgerService ledgerService;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;

    public TransferReversalService(
        LedgerTransactionRepository transactionRepository,
        LedgerEntryRepository entryRepository,
        AppUserRepository userRepository,
        TransferReversalAuditRepository auditRepository,
        UserPermissionService permissionService,
        LedgerService ledgerService,
        NotificationService notificationService,
        PasswordEncoder passwordEncoder,
        MfaService mfaService
    ) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.permissionService = permissionService;
        this.ledgerService = ledgerService;
        this.notificationService = notificationService;
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
    }

    @Transactional
    public LedgerTransaction reverse(
        UUID authorizedByUserId,
        UUID originalTransactionId,
        UUID idempotencyKey,
        ReversalRequest request
    ) {
        permissionService.requirePermission(
            authorizedByUserId, UserPermissionService.TRANSFER_REVERSAL);
        String reason = request.reason().trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_REVERSAL_REASON",
                "El motivo de reversión es obligatorio y no debe exceder 500 caracteres");
        }

        AppUser authorizer = userRepository.findById(authorizedByUserId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario autorizador"));
        if (!passwordEncoder.matches(request.password(), authorizer.getPasswordHash())) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "La contraseña no es válida");
        }
        if (authorizer.isMfaEnabled()) {
            mfaService.verifySensitiveAction(authorizedByUserId, request.mfaFactor());
        } else if (request.mfaFactor() != null && !request.mfaFactor().isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "MFA_NOT_ENABLED",
                "Esta cuenta no tiene MFA activado");
        }

        LedgerTransaction original = transactionRepository.findLockedById(originalTransactionId)
            .orElseThrow(TransferReversalService::transferNotFound);
        if (!"TRANSFER".equals(original.getType())) {
            throw new DomainException(HttpStatus.CONFLICT, "TRANSFER_NOT_COMPLETED",
                "Solo se pueden revertir transferencias completadas");
        }

        String idempotencyHash = TokenHash.sha256(idempotencyKey.toString());
        String fingerprint = fingerprint(originalTransactionId, authorizedByUserId, reason);
        var prior = auditRepository.findByAuthorizedByUserIdAndIdempotencyKeyHash(
            authorizedByUserId, idempotencyHash).orElse(null);
        if (prior != null) {
            if (!originalTransactionId.equals(prior.getOriginalTransactionId())
                || !fingerprint.equals(prior.getRequestFingerprint())) {
                throw idempotencyKeyReused();
            }
            return transactionRepository.findById(prior.getReversalTransactionId())
                .orElseThrow(() -> new IllegalStateException(
                    "La auditoría referencia una reversión inexistente"));
        }
        if (auditRepository.findByOriginalTransactionId(originalTransactionId).isPresent()) {
            throw new DomainException(HttpStatus.CONFLICT, "TRANSFER_ALREADY_REVERSED",
                "La transferencia ya fue revertida");
        }

        List<LedgerEntry> originalEntries =
            entryRepository.findAllByTransaction_IdOrderByIdAsc(originalTransactionId);
        LedgerEntry senderEntry = originalEntries.stream()
            .filter(entry -> entry.getEntryType() == EntryType.DEBIT
                && entry.getAccount().getAccountType() == AccountType.WALLET
                && original.getActorUserId().equals(entry.getAccount().getOwnerUserId()))
            .findFirst()
            .orElseThrow(TransferReversalService::transferNotFound);
        LedgerEntry recipientEntry = originalEntries.stream()
            .filter(entry -> entry.getEntryType() == EntryType.CREDIT
                && entry.getAccount().getAccountType() == AccountType.WALLET)
            .findFirst()
            .orElseThrow(TransferReversalService::transferNotFound);
        if (senderEntry.getAccount().getId().equals(recipientEntry.getAccount().getId())
            || senderEntry.getAmount().compareTo(recipientEntry.getAmount()) != 0) {
            throw new DomainException(HttpStatus.CONFLICT, "TRANSFER_NOT_REVERSIBLE",
                "La transferencia no tiene asientos válidos para revertir");
        }

        UUID senderId = senderEntry.getAccount().getOwnerUserId();
        UUID recipientId = recipientEntry.getAccount().getOwnerUserId();
        AppUser sender = userRepository.findById(senderId)
            .orElseThrow(TransferReversalService::transferNotFound);
        AppUser recipient = userRepository.findById(recipientId)
            .orElseThrow(TransferReversalService::transferNotFound);
        String ledgerIdempotencyKey = TokenHash.sha256(
            authorizedByUserId + ":" + idempotencyKey);
        LedgerTransaction reversal = ledgerService.reverse(
            originalTransactionId,
            original.getActorUserId(),
            authorizedByUserId,
            ledgerIdempotencyKey,
            fingerprint);

        auditRepository.saveAndFlush(new TransferReversalAudit(
            originalTransactionId,
            reversal.getId(),
            authorizedByUserId,
            idempotencyHash,
            fingerprint,
            reason,
            reversal.getCreatedAt()));
        notificationService.notifyTransferReversed(
            senderId, recipientId, reversal.getId(),
            sender.getFullName(), recipient.getFullName(), original.getCurrency(),
            senderEntry.getAmount(), reversal.getCreatedAt());
        return reversal;
    }

    private static String fingerprint(UUID originalId, UUID authorizerId, String reason) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((originalId + "|" + authorizerId + "|" + reason)
                    .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
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
