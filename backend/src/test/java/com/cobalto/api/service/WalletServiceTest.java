package com.cobalto.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobalto.api.domain.AccountType;
import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.LedgerAccount;
import com.cobalto.api.domain.LedgerTransaction;
import com.cobalto.api.dto.TransferRequest;
import com.cobalto.api.dto.TransferResponse;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.LedgerAccountRepository;
import com.cobalto.api.repository.LedgerEntryRepository;
import com.cobalto.api.repository.LedgerTransactionRepository;
import com.cobalto.api.repository.TransferReversalAuditRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Mock private AppUserRepository userRepository;
    @Mock private LedgerAccountRepository accountRepository;
    @Mock private LedgerEntryRepository entryRepository;
    @Mock private LedgerTransactionRepository transactionRepository;
    @Mock private TransferReversalAuditRepository reversalAuditRepository;
    @Mock private LedgerService ledgerService;
    @Mock private NotificationService notificationService;
    @Mock private TransferAuditService auditService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private MfaService mfaService;
    @InjectMocks private WalletService walletService;

    @Test
    void repeatedRequestWithSamePayloadReturnsOriginalTransaction() throws Exception {
        UUID transactionId = UUID.randomUUID();
        var createdAt = java.time.Instant.parse("2026-01-01T10:00:00Z");
        LedgerTransaction previous = org.mockito.Mockito.mock(LedgerTransaction.class);
        when(previous.getRequestFingerprint()).thenReturn(fingerprint());
        when(previous.getType()).thenReturn("TRANSFER");
        when(previous.getId()).thenReturn(transactionId);
        when(previous.getCurrency()).thenReturn("GTQ");
        when(previous.getCreatedAt()).thenReturn(createdAt);
        when(transactionRepository.findByActorUserIdAndIdempotencyKey(SENDER_ID, KEY.toString()))
            .thenReturn(Optional.of(previous));

        TransferResponse result = walletService.transfer(
            SENDER_ID, KEY, new TransferRequest("Recipient@Example.com", AMOUNT, "password", null));

        assertEquals(transactionId, result.transactionId());
        assertEquals("COMPLETED", result.status());
        assertEquals(AMOUNT, result.amount());
        verify(ledgerService, never()).post(
            anyString(), any(), anyString(), any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void idempotencyKeyCannotBeReusedWithDifferentAmount() throws Exception {
        LedgerTransaction previous = org.mockito.Mockito.mock(LedgerTransaction.class);
        when(previous.getRequestFingerprint()).thenReturn(fingerprint());
        when(previous.getType()).thenReturn("TRANSFER");
        when(transactionRepository.findByActorUserIdAndIdempotencyKey(SENDER_ID, KEY.toString()))
            .thenReturn(Optional.of(previous));

        DomainException exception = assertThrows(DomainException.class, () -> walletService.transfer(
            SENDER_ID, KEY,
            new TransferRequest("recipient@example.com", new BigDecimal("11.00"), "password", null)));

        assertEquals("IDEMPOTENCY_KEY_REUSED", exception.getCode());
        verify(ledgerService, never()).post(
            anyString(), any(), anyString(), any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void insufficientFundsDoesNotPostLedgerEntries() {
        UUID recipientId = UUID.randomUUID();
        UUID senderAccountId = UUID.randomUUID();
        UUID recipientAccountId = UUID.randomUUID();
        AppUser recipient = org.mockito.Mockito.mock(AppUser.class);
        when(recipient.getId()).thenReturn(recipientId);
        LedgerAccount sender = new LedgerAccount(senderAccountId, AccountType.WALLET, SENDER_ID, "GTQ");
        LedgerAccount destination = new LedgerAccount(recipientAccountId, AccountType.WALLET, recipientId, "GTQ");

        when(transactionRepository.findByActorUserIdAndIdempotencyKey(SENDER_ID, KEY.toString()))
            .thenReturn(Optional.empty());
        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipient));
        when(accountRepository.findByOwnerUserIdAndCurrency(SENDER_ID, "GTQ"))
            .thenReturn(Optional.of(sender));
        when(accountRepository.findByOwnerUserIdAndCurrency(recipientId, "GTQ"))
            .thenReturn(Optional.of(destination));
        when(accountRepository.findLockedById(senderAccountId)).thenReturn(Optional.of(sender));
        when(accountRepository.findLockedById(recipientAccountId)).thenReturn(Optional.of(destination));
        when(entryRepository.findBalance(senderAccountId)).thenReturn(BigDecimal.ZERO);

        DomainException exception = assertThrows(DomainException.class, () -> walletService.transfer(
            SENDER_ID, KEY, new TransferRequest("recipient@example.com", AMOUNT, "password", null)));

        assertEquals("INSUFFICIENT_FUNDS", exception.getCode());
        verify(ledgerService, never()).post(
            anyString(), any(), anyString(), any(), anyString(), anyString(), any(), any(), any());
    }

    private String fingerprint() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest("recipient@example.com|10|GTQ".getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

}
