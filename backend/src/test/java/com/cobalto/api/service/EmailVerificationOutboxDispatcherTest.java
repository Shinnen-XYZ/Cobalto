package com.cobalto.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobalto.api.security.MfaSecretCipher;
import com.cobalto.api.security.TokenHash;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailVerificationOutboxDispatcherTest {
    private static final String RAW_TOKEN = "opaque-token";

    @Mock private EmailVerificationOutboxCoordinator outboxCoordinator;
    @Mock private MfaSecretCipher secretCipher;
    @Mock private JavaMailSender mailSender;

    @Test
    void verificationTokenIsPlacedInUrlFragmentAndSentToClaimedAddress() {
        EmailVerificationOutboxCoordinator.Claim claim = claim((short) 1);
        EmailVerificationOutboxCoordinator.Delivery delivery = delivery(claim);
        when(outboxCoordinator.claimDue(any(Instant.class), eq(1))).thenReturn(List.of(claim));
        when(outboxCoordinator.prepareDelivery(eq(claim), any(Instant.class)))
            .thenReturn(Optional.of(delivery));
        when(outboxCoordinator.renewLeaseForSend(eq(claim), eq(delivery), any(Instant.class)))
            .thenReturn(true);
        when(secretCipher.decrypt(eq(delivery.userId().toString()), any(byte[].class)))
            .thenReturn(RAW_TOKEN);

        dispatcher().dispatchPending();

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertTrue(message.getValue().getText()
            .contains("http://localhost:8081/verify-email#token=opaque-token"));
        assertFalse(message.getValue().getText().contains("?token=opaque-token"));
        assertTrue(List.of(message.getValue().getTo()).contains(delivery.recipientEmail()));
        verify(outboxCoordinator).markSent(eq(claim), any(Instant.class));
    }

    @Test
    void smtpFailureIsRecordedForRetryWithoutDroppingEncryptedPayload() {
        EmailVerificationOutboxCoordinator.Claim claim = claim((short) 1);
        EmailVerificationOutboxCoordinator.Delivery delivery = delivery(claim);
        when(outboxCoordinator.claimDue(any(Instant.class), eq(1))).thenReturn(List.of(claim));
        when(outboxCoordinator.prepareDelivery(eq(claim), any(Instant.class)))
            .thenReturn(Optional.of(delivery));
        when(outboxCoordinator.renewLeaseForSend(eq(claim), eq(delivery), any(Instant.class)))
            .thenReturn(true);
        when(secretCipher.decrypt(eq(delivery.userId().toString()), any(byte[].class)))
            .thenReturn(RAW_TOKEN);
        doThrow(new MailSendException("local capture server unavailable"))
            .when(mailSender).send(any(SimpleMailMessage.class));

        dispatcher().dispatchPending();

        verify(outboxCoordinator).recordFailure(
            eq(claim), any(Instant.class), eq("MailSendException"), any(Instant.class));
        verify(outboxCoordinator, never()).markSent(any(), any(Instant.class));
    }

    @Test
    void staleOrCancelledClaimIsNeverSent() {
        EmailVerificationOutboxCoordinator.Claim claim = claim((short) 1);
        when(outboxCoordinator.claimDue(any(Instant.class), eq(1))).thenReturn(List.of(claim));
        when(outboxCoordinator.prepareDelivery(eq(claim), any(Instant.class)))
            .thenReturn(Optional.empty());

        dispatcher().dispatchPending();

        verify(outboxCoordinator).cancelClaim(eq(claim), any(Instant.class));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void leaseIsRevalidatedAndRenewedImmediatelyBeforeSending() {
        EmailVerificationOutboxCoordinator.Claim claim = claim((short) 1);
        EmailVerificationOutboxCoordinator.Delivery delivery = delivery(claim);
        when(outboxCoordinator.claimDue(any(Instant.class), eq(1))).thenReturn(List.of(claim));
        when(outboxCoordinator.prepareDelivery(eq(claim), any(Instant.class)))
            .thenReturn(Optional.of(delivery));
        when(secretCipher.decrypt(eq(delivery.userId().toString()), any(byte[].class)))
            .thenReturn(RAW_TOKEN);
        when(outboxCoordinator.renewLeaseForSend(eq(claim), eq(delivery), any(Instant.class)))
            .thenReturn(false);

        dispatcher().dispatchPending();

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(outboxCoordinator).cancelClaim(eq(claim), any(Instant.class));
    }

    private EmailVerificationOutboxCoordinator.Claim claim(short attempts) {
        return new EmailVerificationOutboxCoordinator.Claim(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), attempts);
    }

    private EmailVerificationOutboxCoordinator.Delivery delivery(
        EmailVerificationOutboxCoordinator.Claim claim
    ) {
        return new EmailVerificationOutboxCoordinator.Delivery(
            claim.id(), claim.leaseToken(), UUID.randomUUID(), claim.tokenId(),
            "sandbox@example.test", TokenHash.sha256(RAW_TOKEN), new byte[] {1, 2, 3});
    }

    private EmailVerificationOutboxDispatcher dispatcher() {
        return new EmailVerificationOutboxDispatcher(
            outboxCoordinator,
            secretCipher,
            mailSender,
            "cobalto@localhost",
            "http://localhost:8081/verify-email",
            java.time.Duration.ofMinutes(30));
    }
}
