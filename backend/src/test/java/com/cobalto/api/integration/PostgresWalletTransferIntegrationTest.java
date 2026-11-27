package com.cobalto.api.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.MfaRecoveryCode;
import com.cobalto.api.dto.AuthResponse;
import com.cobalto.api.dto.EmailChangeRequest;
import com.cobalto.api.dto.EmailVerificationResponse;
import com.cobalto.api.dto.RefreshRequest;
import com.cobalto.api.dto.RegisterRequest;
import com.cobalto.api.dto.ReversalRequest;
import com.cobalto.api.dto.TransferReceiptResponse;
import com.cobalto.api.dto.TransferRequest;
import com.cobalto.api.dto.TransferResponse;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.security.MfaSecretCipher;
import com.cobalto.api.security.TokenHash;
import com.cobalto.api.repository.AppNotificationRepository;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.LedgerAccountRepository;
import com.cobalto.api.repository.LedgerEntryRepository;
import com.cobalto.api.repository.MfaRecoveryCodeRepository;
import com.cobalto.api.service.AuthService;
import com.cobalto.api.service.EmailVerificationService;
import com.cobalto.api.service.EmailVerificationOutboxCoordinator;
import com.cobalto.api.service.NotificationService;
import com.cobalto.api.service.TransferReversalService;
import com.cobalto.api.service.WalletService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PostgresWalletTransferIntegrationTest {
    private static final String PASSWORD = "correct horse battery staple";
    private static EmbeddedPostgres postgres;

    @Autowired private AuthService authService;
    @Autowired private EmailVerificationService emailVerificationService;
    @Autowired private EmailVerificationOutboxCoordinator outboxCoordinator;
    @Autowired private WalletService walletService;
    @Autowired private AppUserRepository userRepository;
    @Autowired private LedgerAccountRepository accountRepository;
    @Autowired private LedgerEntryRepository entryRepository;
    @Autowired private AppNotificationRepository notificationRepository;
    @Autowired private MfaRecoveryCodeRepository recoveryCodeRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private TransferReversalService reversalService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MfaSecretCipher secretCipher;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry properties) {
        EmbeddedPostgres database = startPostgres();
        properties.add("spring.datasource.url", () -> database.getJdbcUrl("postgres", "postgres"));
        properties.add("spring.datasource.username", () -> "postgres");
        properties.add("spring.datasource.password", () -> "postgres");
        properties.add("spring.flyway.default-schema", () -> "public");
        properties.add("spring.flyway.schemas", () -> "public");
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        properties.add("COBALTO_JWT_SECRET", () -> testKey);
        properties.add("COBALTO_MFA_ENCRYPTION_KEYS", () -> testKey);
        properties.add("cobalto.email-verification.delivery-enabled", () -> false);
    }

    @Test
    void registrationQueuesOnlyEncryptedTokenAndConfirmationConsumesItOnce() {
        String email = "verify-" + UUID.randomUUID() + "@example.test";
        AuthResponse response = authService.register(
            new RegisterRequest("Verification User", email, PASSWORD));
        AppUser user = userRepository.findByEmail(email).orElseThrow();

        assertFalse(user.isEmailVerified());
        assertFalse(response.user().emailVerified());
        assertEquals(new BigDecimal("100.0000"), balance(user));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_tokens WHERE user_id = ?
            """, Integer.class, user.getId()));

        String token = verificationToken(user);
        String tokenHash = jdbcTemplate.queryForObject("""
            SELECT token_hash FROM auth_email_verification_tokens
            WHERE user_id = ? AND revoked_at IS NULL
            """, String.class, user.getId());
        assertNotEquals(token, tokenHash);
        assertFalse(response.toString().contains(token));

        emailVerificationService.confirm(token);
        AppUser verified = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(verified.isEmailVerified());
        assertNotNull(verified.getEmailVerifiedAt());
        assertThrows(DomainException.class, () -> emailVerificationService.confirm(token));
    }

    @Test
    void changingVerifiedEmailInvalidatesOldVerificationAndStartsNewFlow()
        throws Exception {
        String oldEmail = "email-change-" + UUID.randomUUID() + "@example.test";
        String newEmail = "new-address-" + UUID.randomUUID() + "@example.test";
        AuthResponse session = authService.register(
            new RegisterRequest("Email Change User", oldEmail, PASSWORD));
        AppUser user = userRepository.findByEmail(oldEmail).orElseThrow();
        String oldToken = verificationToken(user);
        verifyEmail(user);
        AppUser recipient = register("Email Change Recipient");
        walletService.transfer(user.getId(), UUID.randomUUID(),
            new TransferRequest(recipient.getEmail(), new BigDecimal("10.00"), PASSWORD, null));
        BigDecimal senderBalance = balance(user);
        BigDecimal recipientBalance = balance(recipient);
        int transactionCount = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_transactions WHERE actor_user_id = ?
            """, Integer.class, user.getId());
        int entryCount = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE a.owner_user_id = ?
            """, Integer.class, user.getId());

        mockMvc.perform(patch("/api/me/email")
                .header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + newEmail + "\",\"password\":\""
                    + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emailVerified").value(false));

        AppUser changedUser = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(newEmail, changedUser.getEmail());
        assertFalse(changedUser.isEmailVerified());
        assertNull(changedUser.getEmailVerifiedAt());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_tokens
            WHERE user_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
            """, Integer.class, changedUser.getId()));

        String newToken = verificationToken(changedUser);
        assertThrows(DomainException.class,
            () -> emailVerificationService.confirm(oldToken));
        emailVerificationService.confirm(newToken);
        assertTrue(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());

        assertEquals(0, balance(user).compareTo(senderBalance));
        assertEquals(0, balance(recipient).compareTo(recipientBalance));
        assertEquals(transactionCount, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_transactions WHERE actor_user_id = ?
            """, Integer.class, user.getId()));
        assertEquals(entryCount, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE a.owner_user_id = ?
            """, Integer.class, user.getId()));
        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_refresh_sessions
            WHERE user_id = ? AND revoked_at IS NULL
            """, Integer.class, user.getId()));
        DomainException revokedSession = assertThrows(DomainException.class,
            () -> authService.refresh(new RefreshRequest(session.refreshToken())));
        assertEquals("INVALID_REFRESH_TOKEN", revokedSession.getCode());
    }

    @Test
    void emailChangeRejectsAnIncorrectCurrentPassword() {
        AppUser user = registerUnverified("Wrong Password User");
        String oldEmail = user.getEmail();
        String requestedEmail = "requested-" + UUID.randomUUID() + "@example.test";

        DomainException exception = assertThrows(DomainException.class,
            () -> authService.changeEmail(user.getId(),
                new EmailChangeRequest(requestedEmail, "wrong-password", null)));

        assertEquals("INVALID_CREDENTIALS", exception.getCode());
        assertEquals(oldEmail, userRepository.findById(user.getId()).orElseThrow().getEmail());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_tokens
            WHERE user_id = ? AND revoked_at IS NULL
            """, Integer.class, user.getId()));
    }

    @Test
    void emailChangeRejectsAnIncorrectMfaFactorAndRecordsTheFailure() {
        AppUser user = registerUnverified("Wrong MFA User");
        user.completeMfaEnrollment(new byte[] {1, 2, 3}, -1);
        userRepository.saveAndFlush(user);
        String invalidRecoveryCode = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF";
        String requestedEmail = "requested-" + UUID.randomUUID() + "@example.test";

        DomainException exception = assertThrows(DomainException.class,
            () -> authService.changeEmail(user.getId(),
                new EmailChangeRequest(requestedEmail, PASSWORD, invalidRecoveryCode)));

        assertEquals("INVALID_MFA_CODE", exception.getCode());
        assertEquals(user.getEmail(), userRepository.findById(user.getId()).orElseThrow().getEmail());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT mfa_failed_attempts FROM app_users WHERE id = ?
            """, Integer.class, user.getId()));
    }

    @Test
    void validMfaRecoveryCodeAuthorizesEmailChangeAndRevokesSessions() {
        String oldEmail = "valid-mfa-" + UUID.randomUUID() + "@example.test";
        AuthResponse session = authService.register(
            new RegisterRequest("Valid MFA User", oldEmail, PASSWORD));
        AppUser user = userRepository.findByEmail(oldEmail).orElseThrow();
        user.completeMfaEnrollment(new byte[] {1, 2, 3}, -1);
        userRepository.saveAndFlush(user);
        String recoveryCode = "89ABCDEF0123456789ABCDEF01234567";
        recoveryCodeRepository.saveAndFlush(new MfaRecoveryCode(
            UUID.randomUUID(), user.getId(), TokenHash.sha256(recoveryCode), Instant.now()));
        String newEmail = "new-valid-mfa-" + UUID.randomUUID() + "@example.test";

        EmailVerificationResponse response = authService.changeEmail(user.getId(),
            new EmailChangeRequest(newEmail, PASSWORD, recoveryCode));

        assertFalse(response.emailVerified());
        AppUser changedUser = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(newEmail, changedUser.getEmail());
        assertFalse(changedUser.isEmailVerified());
        assertNull(changedUser.getEmailVerifiedAt());
        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_refresh_sessions
            WHERE user_id = ? AND revoked_at IS NULL
            """, Integer.class, user.getId()));
        assertTrue(jdbcTemplate.queryForObject("""
            SELECT used_at IS NOT NULL FROM auth_mfa_recovery_codes
            WHERE user_id = ? AND code_hash = ?
            """, Boolean.class, user.getId(), TokenHash.sha256(recoveryCode)));
        assertThrows(DomainException.class,
            () -> authService.refresh(new RefreshRequest(session.refreshToken())));
        emailVerificationService.confirm(verificationToken(changedUser));
        assertTrue(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());
    }

    @Test
    void changingEmailCancelsPendingDeliveryAndCannotReuseItsToken() {
        AppUser user = registerUnverified("Pending Email Change");
        String oldEmail = user.getEmail();
        String oldToken = verificationToken(user);
        UUID oldOutboxId = jdbcTemplate.queryForObject("""
            SELECT id FROM auth_email_verification_outbox
            WHERE user_id = ? AND status = 'PENDING'
            """, UUID.class, user.getId());
        String newEmail = "new-pending-" + UUID.randomUUID() + "@example.test";

        authService.changeEmail(user.getId(),
            new EmailChangeRequest(newEmail, PASSWORD, null));

        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE id = ? AND status = 'CANCELLED' AND encrypted_token IS NULL
                AND lease_token IS NULL AND lease_until IS NULL
            """, Integer.class, oldOutboxId));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_tokens
            WHERE user_id = ? AND token_hash = ? AND revoked_at IS NOT NULL
            """, Integer.class, user.getId(), TokenHash.sha256(oldToken)));
        assertEquals(newEmail, jdbcTemplate.queryForObject("""
            SELECT recipient_email FROM auth_email_verification_outbox
            WHERE user_id = ? AND status = 'PENDING'
            """, String.class, user.getId()));
        assertThrows(DomainException.class, () -> emailVerificationService.confirm(oldToken));
        AppUser changedUser = userRepository.findById(user.getId()).orElseThrow();
        emailVerificationService.confirm(verificationToken(changedUser));
        assertTrue(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertNotEquals(oldEmail, changedUser.getEmail());
    }

    @Test
    void changingEmailRevokesAClaimAfterPreparationAndBeforeLeaseRenewal()
        throws Exception {
        postponeAllPendingDeliveries();
        AppUser user = registerUnverified("Claimed Email Change");
        EmailVerificationOutboxCoordinator.Claim claim =
            outboxCoordinator.claimDue(Instant.now(), 1).get(0);
        EmailVerificationOutboxCoordinator.Delivery prepared =
            outboxCoordinator.prepareDelivery(claim, Instant.now()).orElseThrow();
        assertTrue(outboxCoordinator.renewLeaseForSend(claim, prepared, Instant.now()));

        authService.changeEmail(user.getId(), new EmailChangeRequest(
            "claimed-change-" + UUID.randomUUID() + "@example.test", PASSWORD, null));

        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE id = ? AND status = 'CANCELLED' AND encrypted_token IS NULL
                AND lease_token IS NULL AND lease_until IS NULL
            """, Integer.class, claim.id()));
        assertTrue(outboxCoordinator.prepareDelivery(claim, Instant.now()).isEmpty());
        assertFalse(outboxCoordinator.renewLeaseForSend(claim, prepared, Instant.now()));
    }

    @Test
    void concurrentWorkersCannotClaimTheSameOutboxRecord() throws Exception {
        postponeAllPendingDeliveries();
        AppUser user = registerUnverified("Concurrent Outbox Claim");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<List<EmailVerificationOutboxCoordinator.Claim>> claim = () -> {
                start.await();
                return outboxCoordinator.claimDue(Instant.now(), 1);
            };
            Future<List<EmailVerificationOutboxCoordinator.Claim>> first =
                executor.submit(claim);
            Future<List<EmailVerificationOutboxCoordinator.Claim>> second =
                executor.submit(claim);
            start.countDown();
            List<EmailVerificationOutboxCoordinator.Claim> claims = new ArrayList<>();
            claims.addAll(first.get(30, TimeUnit.SECONDS));
            claims.addAll(second.get(30, TimeUnit.SECONDS));

            assertEquals(1, claims.size());
            assertEquals(user.getId(), jdbcTemplate.queryForObject("""
                SELECT user_id FROM auth_email_verification_outbox WHERE id = ?
                """, UUID.class, claims.get(0).id()));
            assertEquals("PROCESSING", jdbcTemplate.queryForObject("""
                SELECT status FROM auth_email_verification_outbox WHERE id = ?
                """, String.class, claims.get(0).id()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseRecoversWithBackoffAndFencesTheOldWorker() {
        postponeAllPendingDeliveries();
        AppUser user = registerUnverified("Expired Outbox Lease");
        Instant now = Instant.now();
        EmailVerificationOutboxCoordinator.Claim original =
            outboxCoordinator.claimDue(now, 1).get(0);
        jdbcTemplate.update("""
            UPDATE auth_email_verification_outbox SET lease_until = ?
            WHERE id = ? AND lease_token = ?
            """, java.sql.Timestamp.from(now.minusSeconds(1)),
            original.id(), original.leaseToken());

        assertTrue(outboxCoordinator.claimDue(now, 1).isEmpty());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE id = ? AND status = 'PENDING' AND attempts = 1
                AND next_attempt_at > ? AND lease_token IS NULL
            """, Integer.class, original.id(), java.sql.Timestamp.from(now)));

        jdbcTemplate.update("""
            UPDATE auth_email_verification_outbox SET next_attempt_at = ?
            WHERE id = ? AND status = 'PENDING'
            """, java.sql.Timestamp.from(now.minusSeconds(1)), original.id());
        EmailVerificationOutboxCoordinator.Claim reclaimed =
            outboxCoordinator.claimDue(now, 1).get(0);

        assertEquals(original.id(), reclaimed.id());
        assertNotEquals(original.leaseToken(), reclaimed.leaseToken());
        assertEquals(2, reclaimed.attempts());
        assertEquals(user.getId(), jdbcTemplate.queryForObject("""
            SELECT user_id FROM auth_email_verification_outbox WHERE id = ?
            """, UUID.class, reclaimed.id()));

        outboxCoordinator.markSent(original, now);
        assertFalse(outboxCoordinator.recordFailure(
            original, now.plusSeconds(30), "OldWorkerFailure", now));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE id = ? AND status = 'PROCESSING' AND attempts = 2 AND lease_token = ?
            """, Integer.class, reclaimed.id(), reclaimed.leaseToken()));

        outboxCoordinator.markSent(reclaimed, now);
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE id = ? AND status = 'SENT' AND encrypted_token IS NULL
            """, Integer.class, reclaimed.id()));
    }

    @Test
    void sentFailedAndCancelledOutboxRowsAreNeverClaimedAgain() {
        postponeAllPendingDeliveries();
        AppUser sent = registerUnverified("Outbox Sent Terminal");
        AppUser failed = registerUnverified("Outbox Failed Terminal");
        AppUser cancelled = registerUnverified("Outbox Cancelled Terminal");

        jdbcTemplate.update("""
            UPDATE auth_email_verification_outbox
            SET status = 'SENT', encrypted_token = NULL, sent_at = ?
            WHERE user_id = ?
            """, java.sql.Timestamp.from(Instant.now()), sent.getId());
        jdbcTemplate.update("""
            UPDATE auth_email_verification_outbox
            SET status = 'FAILED', encrypted_token = NULL
            WHERE user_id = ?
            """, failed.getId());
        jdbcTemplate.update("""
            UPDATE auth_email_verification_outbox
            SET status = 'CANCELLED', encrypted_token = NULL
            WHERE user_id = ?
            """, cancelled.getId());

        assertTrue(outboxCoordinator.claimDue(Instant.now(), 1).isEmpty());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE user_id = ? AND status = 'SENT'
            """, Integer.class, sent.getId()));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE user_id = ? AND status = 'FAILED'
            """, Integer.class, failed.getId()));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE user_id = ? AND status = 'CANCELLED'
            """, Integer.class, cancelled.getId()));
    }

    @Test
    void abandonedLeasesReachFiveAttemptsThenFailAndRevokeTheToken() {
        postponeAllPendingDeliveries();
        AppUser user = registerUnverified("Outbox Attempt Limit");
        UUID outboxId = jdbcTemplate.queryForObject("""
            SELECT id FROM auth_email_verification_outbox WHERE user_id = ?
            """, UUID.class, user.getId());
        Instant now = Instant.now();

        for (short attempt = 1; attempt <= 5; attempt++) {
            jdbcTemplate.update("""
                UPDATE auth_email_verification_outbox
                SET next_attempt_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, java.sql.Timestamp.from(now.minusSeconds(1)), outboxId);
            EmailVerificationOutboxCoordinator.Claim claim =
                outboxCoordinator.claimDue(now, 1).get(0);
            assertEquals(attempt, claim.attempts());
            jdbcTemplate.update("""
                UPDATE auth_email_verification_outbox SET lease_until = ?
                WHERE id = ? AND lease_token = ?
                """, java.sql.Timestamp.from(now.minusSeconds(1)), outboxId,
                claim.leaseToken());

            assertTrue(outboxCoordinator.claimDue(now, 1).isEmpty());
        }

        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE id = ? AND status = 'FAILED' AND attempts = 5
                AND encrypted_token IS NULL AND lease_token IS NULL AND lease_until IS NULL
            """, Integer.class, outboxId));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_tokens
            WHERE user_id = ? AND revoked_at IS NOT NULL
            """, Integer.class, user.getId()));
    }

    @Test
    void smtpFailureUsesBackoffAndPreservesEncryptedTokenForRetry() {
        postponeAllPendingDeliveries();
        AppUser user = registerUnverified("Outbox SMTP Retry");
        Instant now = Instant.now();
        EmailVerificationOutboxCoordinator.Claim claim =
            outboxCoordinator.claimDue(now, 1).get(0);

        assertFalse(outboxCoordinator.recordFailure(
            claim, now.plusSeconds(30), "MailSendException", now));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM auth_email_verification_outbox
            WHERE id = ? AND status = 'PENDING' AND attempts = 1
                AND encrypted_token IS NOT NULL AND lease_token IS NULL
                AND next_attempt_at > ?
            """, Integer.class, claim.id(), java.sql.Timestamp.from(now)));

        EmailVerificationOutboxCoordinator.Claim retry =
            outboxCoordinator.claimDue(now.plusSeconds(31), 1).get(0);
        assertEquals(claim.id(), retry.id());
        assertEquals(user.getId(), jdbcTemplate.queryForObject("""
            SELECT user_id FROM auth_email_verification_outbox WHERE id = ?
            """, UUID.class, retry.id()));
    }

    @Test
    void expiredAndRevokedEmailVerificationTokensCannotBeConsumed() {
        AppUser expiredUser = registerUnverified("Expired Verification");
        String expiredToken = verificationToken(expiredUser);
        jdbcTemplate.update("""
            UPDATE auth_email_verification_tokens
            SET created_at = ?, expires_at = ?
            WHERE user_id = ?
            """, java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)), expiredUser.getId());
        assertThrows(DomainException.class,
            () -> emailVerificationService.confirm(expiredToken));

        AppUser revokedUser = registerUnverified("Revoked Verification");
        String revokedToken = verificationToken(revokedUser);
        jdbcTemplate.update("""
            UPDATE auth_email_verification_tokens
            SET revoked_at = ?
            WHERE user_id = ?
            """, java.sql.Timestamp.from(Instant.now()), revokedUser.getId());
        assertThrows(DomainException.class,
            () -> emailVerificationService.confirm(revokedToken));
    }

    @Test
    void resendInvalidatesPreviousTokenAndEnforcesCooldownAndDailyLimit() {
        AppUser user = registerUnverified("Resend Verification");
        String originalToken = verificationToken(user);

        DomainException cooldown = assertThrows(DomainException.class,
            () -> emailVerificationService.resend(user.getId()));
        assertEquals("EMAIL_VERIFICATION_COOLDOWN", cooldown.getCode());

        for (int index = 0; index < 5; index++) {
            jdbcTemplate.update("""
                UPDATE auth_email_verification_tokens
                SET created_at = ?
                WHERE user_id = ? AND id = (
                    SELECT id FROM auth_email_verification_tokens
                    WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
                )
                """, java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
                user.getId(), user.getId());
            assertFalse(emailVerificationService.resend(user.getId()).emailVerified());
        }

        jdbcTemplate.update("""
            UPDATE auth_email_verification_tokens
            SET created_at = ?
            WHERE user_id = ? AND id = (
                SELECT id FROM auth_email_verification_tokens
                WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
            )
            """, java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
            user.getId(), user.getId());
        DomainException dailyLimit = assertThrows(DomainException.class,
            () -> emailVerificationService.resend(user.getId()));
        assertEquals("EMAIL_VERIFICATION_LIMIT", dailyLimit.getCode());

        String currentToken = verificationToken(user);
        assertThrows(DomainException.class,
            () -> emailVerificationService.confirm(originalToken));
        emailVerificationService.confirm(currentToken);
        assertTrue(userRepository.findById(user.getId()).orElseThrow().isEmailVerified());
    }

    @Test
    void unverifiedRecipientCannotBeConfirmedOrReachTheLedger() {
        AppUser sender = register("Verified Sender");
        AppUser recipient = registerUnverified("Unverified Recipient");
        BigDecimal senderBalance = balance(sender);
        UUID key = UUID.randomUUID();

        DomainException confirmation = assertThrows(DomainException.class,
            () -> walletService.confirmRecipient(sender.getId(), recipient.getEmail()));
        assertEquals("RECIPIENT_NOT_FOUND", confirmation.getCode());
        DomainException transfer = assertThrows(DomainException.class,
            () -> walletService.transfer(sender.getId(), key,
                new TransferRequest(recipient.getEmail(), BigDecimal.ONE, PASSWORD, null)));
        assertEquals("RECIPIENT_NOT_FOUND", transfer.getCode());
        assertEquals(senderBalance, balance(sender));
        assertEquals(0, transferCount(sender.getId(), key));
        assertEquals(0, countEntriesForTransferAttempt(sender.getId(), key));

        emailVerificationService.confirm(verificationToken(recipient));
        TransferResponse result = walletService.transfer(
            sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipient.getEmail(), BigDecimal.ONE, PASSWORD, null));
        assertEquals("COMPLETED", result.status());
    }

    @Test
    void verificationEndpointsAllowPublicConfirmationAndRequireAuthenticationForResend()
        throws Exception {
        String email = "endpoint-" + UUID.randomUUID() + "@example.test";
        AuthResponse session = authService.register(
            new RegisterRequest("Endpoint User", email, PASSWORD));
        AppUser user = userRepository.findByEmail(email).orElseThrow();
        String token = verificationToken(user);

        mockMvc.perform(post("/api/auth/email-verification/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emailVerified").value(true));
        mockMvc.perform(post("/api/auth/email-verification/resend"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/email-verification/resend")
                .header("Authorization", "Bearer " + session.accessToken()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    void concurrentDuplicateRegistrationReturnsOneAccountAndOneConflict() throws Exception {
        String email = "concurrent-" + UUID.randomUUID() + "@example.test";
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<String> registration = () -> {
                start.await();
                try {
                    authService.register(new RegisterRequest("Concurrent User", email, PASSWORD));
                    return "CREATED";
                } catch (DomainException exception) {
                    return exception.getCode();
                }
            };
            Future<String> first = executor.submit(registration);
            Future<String> second = executor.submit(registration);
            start.countDown();
            List<String> results = List.of(first.get(30, TimeUnit.SECONDS),
                second.get(30, TimeUnit.SECONDS));

            assertEquals(1L, results.stream().filter("CREATED"::equals).count());
            assertEquals(1L, results.stream().filter("EMAIL_IN_USE"::equals).count());
            AppUser user = userRepository.findByEmail(email).orElseThrow();
            assertFalse(user.isEmailVerified());
            assertEquals(1, walletCount(user.getId()));
            assertEquals(new BigDecimal("100.0000"), balance(user));
        } finally {
            executor.shutdownNow();
        }
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void transferPostsBalancedLedgerNotifiesBothUsersAndReturnsPrivateReceipts() {
        AppUser sender = register("Sender");
        AppUser recipient = register("Recipient");
        UUID idempotencyKey = UUID.randomUUID();

        var confirmation = walletService.confirmRecipient(sender.getId(), recipient.getEmail());
        assertEquals(recipient.getFullName(), confirmation.fullName());
        assertEquals(mask(recipient.getEmail()), confirmation.maskedEmail());

        TransferResponse result = walletService.transfer(
            sender.getId(), idempotencyKey,
            new TransferRequest(recipient.getEmail(), new BigDecimal("12.35"), PASSWORD, null));

        assertEquals("COMPLETED", result.status());
        assertBalance(sender, "87.6500");
        assertBalance(recipient, "112.3500");
        assertEquals(2, notificationRepository
            .findTop50ByUserIdOrderByCreatedAtDesc(sender.getId()).size()
            + notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(recipient.getId()).size());
        assertEquals(2, countEntries(result.transactionId()));
        assertEquals(0, countUnbalancedTransactions());

        TransferReceiptResponse senderReceipt =
            walletService.getTransferDetails(sender.getId(), result.transactionId());
        TransferReceiptResponse recipientReceipt =
            walletService.getTransferDetails(recipient.getId(), result.transactionId());
        assertEquals("SENT", senderReceipt.direction());
        assertEquals("RECEIVED", recipientReceipt.direction());
        assertEquals("COMPLETED", senderReceipt.status());
        assertEquals(result.transactionId().toString().toUpperCase(),
            senderReceipt.receiptNumber().substring(3));
        assertEquals(mask(recipient.getEmail()), senderReceipt.maskedCounterpartyEmail());
        assertEquals(mask(sender.getEmail()), recipientReceipt.maskedCounterpartyEmail());
        assertEquals(2, auditEventCount(sender.getId(), idempotencyKey));
    }

    @Test
    void insufficientFundsAndMissingRecipientsLeaveNoLedgerPosting() {
        AppUser sender = register("Insufficient");
        AppUser recipient = register("Valid Recipient");
        UUID insufficientKey = UUID.randomUUID();
        DomainException insufficient = assertThrows(DomainException.class,
            () -> walletService.transfer(sender.getId(), insufficientKey,
                new TransferRequest(recipient.getEmail(), new BigDecimal("100.01"), PASSWORD, null)));
        assertEquals("INSUFFICIENT_FUNDS", insufficient.getCode());
        assertEquals("100.0000", balance(sender).toPlainString());
        assertEquals(0, transferCount(sender.getId(), insufficientKey));
        assertEquals(2, auditEventCount(sender.getId(), insufficientKey));

        UUID missingKey = UUID.randomUUID();
        DomainException missing = assertThrows(DomainException.class,
            () -> walletService.transfer(sender.getId(), missingKey,
                new TransferRequest("missing@example.test", BigDecimal.ONE, PASSWORD, null)));
        assertEquals("RECIPIENT_NOT_FOUND", missing.getCode());
        assertEquals(0, transferCount(sender.getId(), missingKey));
        assertEquals(2, auditEventCount(sender.getId(), missingKey));
    }

    @Test
    void idempotencyReplaysOneTransferAndRejectsChangedPayload() {
        AppUser sender = register("Idempotent Sender");
        AppUser recipient = register("Idempotent Recipient");
        UUID key = UUID.randomUUID();
        TransferRequest original = new TransferRequest(
            recipient.getEmail(), new BigDecimal("10.00"), PASSWORD, null);

        TransferResponse first = walletService.transfer(sender.getId(), key, original);
        TransferResponse replay = walletService.transfer(sender.getId(), key, original);

        assertEquals(first.transactionId(), replay.transactionId());
        assertEquals(1, transferCount(sender.getId(), key));
        assertBalance(sender, "90.0000");
        assertBalance(recipient, "110.0000");
        assertEquals(1, notificationRepository
            .findTop50ByUserIdOrderByCreatedAtDesc(sender.getId()).size());

        DomainException changed = assertThrows(DomainException.class,
            () -> walletService.transfer(sender.getId(), key,
                new TransferRequest(recipient.getEmail(), new BigDecimal("11.00"), PASSWORD, null)));
        assertEquals("IDEMPOTENCY_KEY_REUSED", changed.getCode());
    }

    @Test
    void transferRequiresPasswordAndConsumesMfaRecoveryForMfaEnabledUsers() {
        AppUser sender = register("Step-Up Sender");
        AppUser recipient = register("Step-Up Recipient");
        UUID key = UUID.randomUUID();

        DomainException invalidPassword = assertThrows(DomainException.class,
            () -> walletService.transfer(sender.getId(), key,
                new TransferRequest(recipient.getEmail(), BigDecimal.ONE, "wrong-password", null)));
        assertEquals("INVALID_CREDENTIALS", invalidPassword.getCode());
        assertEquals(0, transferCount(sender.getId(), key));

        sender.completeMfaEnrollment(new byte[] {1, 2, 3}, -1);
        userRepository.saveAndFlush(sender);
        String recoveryCode = "89ABCDEF0123456789ABCDEF01234567";
        String recoveryHash = TokenHash.sha256(recoveryCode);
        recoveryCodeRepository.saveAndFlush(new MfaRecoveryCode(
            UUID.randomUUID(), sender.getId(), recoveryHash, java.time.Instant.now()));

        TransferResponse result = walletService.transfer(sender.getId(), key,
            new TransferRequest(recipient.getEmail(), BigDecimal.ONE, PASSWORD, recoveryCode));

        assertEquals("COMPLETED", result.status());
        assertTrue(jdbcTemplate.queryForObject("""
            SELECT used_at IS NOT NULL FROM auth_mfa_recovery_codes
            WHERE user_id = ? AND code_hash = ?
            """, Boolean.class, sender.getId(), recoveryHash));
        assertBalance(sender, "99.0000");
        assertBalance(recipient, "101.0000");
    }

    @Test
    void simultaneousSameKeyAndOverspendingTransfersRemainAtomic() throws Exception {
        AppUser sender = register("Concurrent Sender");
        AppUser recipient = register("Concurrent Recipient");
        UUID sameKey = UUID.randomUUID();
        List<TransferResponse> repeated = executeConcurrently(List.of(
            () -> walletService.transfer(sender.getId(), sameKey,
                new TransferRequest(recipient.getEmail(), new BigDecimal("10.00"), PASSWORD, null)),
            () -> walletService.transfer(sender.getId(), sameKey,
                new TransferRequest(recipient.getEmail(), new BigDecimal("10.00"), PASSWORD, null))));
        assertEquals(repeated.get(0).transactionId(), repeated.get(1).transactionId());
        assertEquals(1, transferCount(sender.getId(), sameKey));
        assertBalance(sender, "90.0000");

        AppUser sender2 = register("Overspending Sender");
        AppUser recipient2 = register("Overspending Recipient 1");
        AppUser recipient3 = register("Overspending Recipient 2");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                () -> overspendAttempt(start, sender2, recipient2),
                () -> overspendAttempt(start, sender2, recipient3));
            List<Future<Boolean>> results = attempts.stream().map(executor::submit).toList();
            start.countDown();
            List<Boolean> completed = new ArrayList<>();
            for (Future<Boolean> result : results) {
                completed.add(result.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, completed.stream().filter(Boolean::booleanValue).count());
            assertBalance(sender2, "20.0000");
            assertTrue(balance(recipient2).add(balance(recipient3))
                .compareTo(new BigDecimal("280.0000")) == 0);
            assertEquals(0, countUnbalancedTransactions());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void databaseFailureRollsBackLedgerAndNotificationsAndKeepsFailureAudit() throws Exception {
        AppUser sender = register("Rollback Sender");
        AppUser recipient = register("Rollback Recipient");
        UUID key = UUID.randomUUID();
        installNotificationFailureTrigger();
        try {
            assertThrows(RuntimeException.class, () -> walletService.transfer(sender.getId(), key,
                new TransferRequest(recipient.getEmail(), new BigDecimal("9.00"), PASSWORD, null)));
        } finally {
            dropNotificationFailureTrigger();
        }

        assertBalance(sender, "100.0000");
        assertBalance(recipient, "100.0000");
        assertEquals(0, transferCount(sender.getId(), key));
        assertEquals(0, notificationRepository
            .findTop50ByUserIdOrderByCreatedAtDesc(sender.getId()).size());
        assertEquals(2, auditEventCount(sender.getId(), key));
    }

    @Test
    void transferDetailsAndNotificationsCannotBeReadOrChangedByAnotherUser() {
        AppUser sender = register("Owner Sender");
        AppUser recipient = register("Owner Recipient");
        AppUser unrelated = register("Unrelated User");
        TransferResponse result = walletService.transfer(
            sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipient.getEmail(), BigDecimal.ONE, PASSWORD, null));

        DomainException hiddenTransfer = assertThrows(DomainException.class,
            () -> walletService.getTransferDetails(unrelated.getId(), result.transactionId()));
        assertEquals("TRANSFER_NOT_FOUND", hiddenTransfer.getCode());

        UUID recipientNotification = notificationRepository
            .findTop50ByUserIdOrderByCreatedAtDesc(recipient.getId()).get(0).getId();
        DomainException hiddenNotification = assertThrows(DomainException.class,
            () -> notificationService.markRead(unrelated.getId(), recipientNotification));
        assertEquals("NOTIFICATION_NOT_FOUND", hiddenNotification.getCode());
    }

    @Test
    void authenticatedWalletAndReceiptRoutesDoNotAllowIdor() throws Exception {
        String senderEmail = "http-sender-" + UUID.randomUUID() + "@example.test";
        String recipientEmail = "http-recipient-" + UUID.randomUUID() + "@example.test";
        String unrelatedEmail = "http-other-" + UUID.randomUUID() + "@example.test";
        AuthResponse senderSession = authService.register(
            new RegisterRequest("HTTP Sender", senderEmail, PASSWORD));
        AuthResponse recipientSession = authService.register(
            new RegisterRequest("HTTP Recipient", recipientEmail, PASSWORD));
        AuthResponse unrelatedSession = authService.register(
            new RegisterRequest("HTTP Unrelated", unrelatedEmail, PASSWORD));
        AppUser sender = userRepository.findByEmail(senderEmail).orElseThrow();
        AppUser recipient = userRepository.findByEmail(recipientEmail).orElseThrow();
        verifyEmail(recipient);
        TransferResponse transfer = walletService.transfer(
            sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipientEmail, BigDecimal.ONE, PASSWORD, null));

        mockMvc.perform(get("/api/wallet")
                .param("userId", sender.getId().toString())
                .header("Authorization", "Bearer " + recipientSession.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(101.0));
        mockMvc.perform(get("/api/transfers/{id}", transfer.transactionId())
                .header("Authorization", "Bearer " + unrelatedSession.accessToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        assertNotEquals(sender.getId(), recipient.getId());
    }

    @Test
    void authorizedReversalCreatesExactOppositeEntriesAndPrivateReceipts() {
        AppUser sender = register("Reversal Sender");
        AppUser recipient = register("Reversal Recipient");
        AppUser authorizer = authorizeReverser("Reversal Operator");
        TransferResponse transfer = walletService.transfer(
            sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipient.getEmail(), new BigDecimal("12.35"), PASSWORD, null));
        int originalEntryCount = countEntries(transfer.transactionId());
        UUID idempotencyKey = UUID.randomUUID();

        var reversal = reversalService.reverse(authorizer.getId(), transfer.transactionId(),
            idempotencyKey, new ReversalRequest("Transferencia duplicada", PASSWORD, null));

        assertEquals("REVERSAL", reversal.getType());
        assertEquals(transfer.transactionId(), reversal.getReversalOfTransactionId());
        assertEquals(authorizer.getId(), reversal.getReversalAuthorizedByUserId());
        assertBalance(sender, "100.0000");
        assertBalance(recipient, "100.0000");
        assertEquals(originalEntryCount, countEntries(transfer.transactionId()));
        assertEquals(2, countEntries(reversal.getId()));
        assertEquals(2, jdbcTemplate.queryForObject("""
            SELECT count(*)
            FROM ledger_entries reversed
            JOIN ledger_entries original
              ON original.account_id = reversed.account_id
             AND original.entry_type <> reversed.entry_type
             AND original.amount = reversed.amount
            WHERE reversed.transaction_id = ?
              AND original.transaction_id = ?
            """, Integer.class, reversal.getId(), transfer.transactionId()));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM transfer_reversal_audits
            WHERE original_transaction_id = ? AND reversal_transaction_id = ?
              AND authorized_by_user_id = ? AND reason = ?
            """, Integer.class, transfer.transactionId(), reversal.getId(),
            authorizer.getId(), "Transferencia duplicada"));
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update("""
            UPDATE transfer_reversal_audits SET reason = 'alterado'
            WHERE reversal_transaction_id = ?
            """, reversal.getId()));
        assertEquals(4, notificationRepository
            .findTop50ByUserIdOrderByCreatedAtDesc(sender.getId()).size()
            + notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(recipient.getId()).size());

        var originalReceipt = walletService.getTransferDetails(
            sender.getId(), transfer.transactionId());
        var senderReversalReceipt = walletService.getTransferDetails(sender.getId(), reversal.getId());
        var recipientReversalReceipt =
            walletService.getTransferDetails(recipient.getId(), reversal.getId());
        var authorizerReceipt =
            walletService.getTransferDetails(authorizer.getId(), reversal.getId());
        assertEquals("REVERSED", originalReceipt.status());
        assertEquals("REVERSAL", senderReversalReceipt.operationType());
        assertEquals("REVERSAL_CREDIT", senderReversalReceipt.direction());
        assertEquals("REVERSAL_DEBIT", recipientReversalReceipt.direction());
        assertEquals(transfer.transactionId(), senderReversalReceipt.originalTransactionId());
        assertEquals("Transferencia duplicada", senderReversalReceipt.reason());
        assertEquals("CB-RV-" + reversal.getId().toString().toUpperCase(),
            senderReversalReceipt.receiptNumber());
        assertEquals("Operación autorizada", authorizerReceipt.counterpartyName());
        assertEquals(1, walletService.listMovements(sender.getId(), 100, 0).stream()
            .filter(movement -> movement.transactionId().equals(reversal.getId())).count());
        assertEquals(1, walletService.listMovements(recipient.getId(), 100, 0).stream()
            .filter(movement -> movement.transactionId().equals(reversal.getId())).count());
    }

    @Test
    void reversalRejectsMissingNonTransferAndUnauthorizedRequests() {
        AppUser sender = register("Reversal Validation Sender");
        AppUser recipient = register("Reversal Validation Recipient");
        AppUser operator = authorizeReverser("Reversal Validation Operator");
        TransferResponse transfer = walletService.transfer(
            sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipient.getEmail(), new BigDecimal("3.00"), PASSWORD, null));
        ReversalRequest request = new ReversalRequest("Motivo válido", PASSWORD, null);

        AppUser regularUser = register("No Reversal Permission");
        DomainException unauthorized = assertThrows(DomainException.class,
            () -> reversalService.reverse(regularUser.getId(), transfer.transactionId(),
                UUID.randomUUID(), request));
        assertEquals("PERMISSION_REQUIRED", unauthorized.getCode());

        DomainException missing = assertThrows(DomainException.class,
            () -> reversalService.reverse(operator.getId(), UUID.randomUUID(),
                UUID.randomUUID(), request));
        assertEquals("TRANSFER_NOT_FOUND", missing.getCode());

        UUID sandboxCreditId = jdbcTemplate.queryForObject("""
            SELECT id FROM ledger_transactions
            WHERE actor_user_id = ? AND type = 'SANDBOX_CREDIT'
            """, UUID.class, sender.getId());
        DomainException notTransfer = assertThrows(DomainException.class,
            () -> reversalService.reverse(operator.getId(), sandboxCreditId,
                UUID.randomUUID(), request));
        assertEquals("TRANSFER_NOT_COMPLETED", notTransfer.getCode());
        assertEquals(0, reversalAuditCount(transfer.transactionId()));
        assertBalance(sender, "97.0000");
        assertBalance(recipient, "103.0000");
    }

    @Test
    void reversalIsIdempotentRejectsChangedPayloadAndPreventsSecondReversal() {
        AppUser sender = register("Reversal Idempotent Sender");
        AppUser recipient = register("Reversal Idempotent Recipient");
        AppUser operator = authorizeReverser("Reversal Idempotent Operator");
        TransferResponse transfer = walletService.transfer(
            sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipient.getEmail(), new BigDecimal("7.00"), PASSWORD, null));
        UUID key = UUID.randomUUID();
        ReversalRequest request = new ReversalRequest("Prueba idempotente", PASSWORD, null);

        var first = reversalService.reverse(operator.getId(), transfer.transactionId(), key, request);
        var replay = reversalService.reverse(operator.getId(), transfer.transactionId(), key, request);
        assertEquals(first.getId(), replay.getId());
        assertEquals(1, reversalAuditCount(transfer.transactionId()));

        DomainException changedRequest = assertThrows(DomainException.class,
            () -> reversalService.reverse(operator.getId(), transfer.transactionId(), key,
                new ReversalRequest("Motivo diferente", PASSWORD, null)));
        assertEquals("IDEMPOTENCY_KEY_REUSED", changedRequest.getCode());

        DomainException duplicate = assertThrows(DomainException.class,
            () -> reversalService.reverse(operator.getId(), transfer.transactionId(),
                UUID.randomUUID(), request));
        assertEquals("TRANSFER_ALREADY_REVERSED", duplicate.getCode());
        assertBalance(sender, "100.0000");
        assertBalance(recipient, "100.0000");
        assertEquals(1, reversalAuditCount(transfer.transactionId()));
    }

    @Test
    void concurrentReversalRequestsPublishOnlyOneReversal() throws Exception {
        AppUser sender = register("Concurrent Reversal Sender");
        AppUser recipient = register("Concurrent Reversal Recipient");
        AppUser operator = authorizeReverser("Concurrent Reversal Operator");
        TransferResponse transfer = walletService.transfer(
            sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipient.getEmail(), new BigDecimal("20.00"), PASSWORD, null));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> attempts = List.of(
                () -> reverseAttempt(start, operator, transfer, UUID.randomUUID()),
                () -> reverseAttempt(start, operator, transfer, UUID.randomUUID()));
            List<Future<String>> results = attempts.stream().map(executor::submit).toList();
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, outcomes.stream().filter("COMPLETED"::equals).count());
            assertEquals(1, outcomes.stream()
                .filter("TRANSFER_ALREADY_REVERSED"::equals).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, reversalAuditCount(transfer.transactionId()));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_transactions
            WHERE reversal_of_transaction_id = ?
            """, Integer.class, transfer.transactionId()));
        assertBalance(sender, "100.0000");
        assertBalance(recipient, "100.0000");
    }

    @Test
    void notificationFailureRollsBackReversalLedgerAndAudit() throws Exception {
        AppUser sender = register("Reversal Rollback Sender");
        AppUser recipient = register("Reversal Rollback Recipient");
        AppUser operator = authorizeReverser("Reversal Rollback Operator");
        TransferResponse transfer = walletService.transfer(
            sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipient.getEmail(), new BigDecimal("9.00"), PASSWORD, null));
        installNotificationFailureTrigger();
        try {
            assertThrows(RuntimeException.class, () -> reversalService.reverse(
                operator.getId(), transfer.transactionId(), UUID.randomUUID(),
                new ReversalRequest("Falla inyectada", PASSWORD, null)));
        } finally {
            dropNotificationFailureTrigger();
        }

        assertBalance(sender, "91.0000");
        assertBalance(recipient, "109.0000");
        assertEquals(0, reversalAuditCount(transfer.transactionId()));
        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_transactions
            WHERE reversal_of_transaction_id = ?
            """, Integer.class, transfer.transactionId()));
        assertEquals(1, notificationRepository
            .findTop50ByUserIdOrderByCreatedAtDesc(sender.getId()).size());
        assertEquals(1, notificationRepository
            .findTop50ByUserIdOrderByCreatedAtDesc(recipient.getId()).size());
    }

    @Test
    void reversalRoutesEnforcePermissionAndHideReceiptsFromUnrelatedUsers() throws Exception {
        String senderEmail = "reversal-http-sender-" + UUID.randomUUID() + "@example.test";
        String recipientEmail = "reversal-http-recipient-" + UUID.randomUUID() + "@example.test";
        AuthResponse senderSession = authService.register(
            new RegisterRequest("HTTP Reversal Sender", senderEmail, PASSWORD));
        AuthResponse recipientSession = authService.register(
            new RegisterRequest("HTTP Reversal Recipient", recipientEmail, PASSWORD));
        AuthResponse operatorSession = authService.register(
            new RegisterRequest("HTTP Reversal Operator", "reversal-operator-"
                + UUID.randomUUID() + "@example.test", PASSWORD));
        AuthResponse unrelatedSession = authService.register(
            new RegisterRequest("HTTP Reversal Unrelated", "reversal-unrelated-"
                + UUID.randomUUID() + "@example.test", PASSWORD));
        AppUser sender = userRepository.findByEmail(senderEmail).orElseThrow();
        AppUser recipient = userRepository.findByEmail(recipientEmail).orElseThrow();
        verifyEmail(recipient);
        AppUser operator = userRepository.findByEmail(operatorSession.user().email()).orElseThrow();
        AppUser unrelated = userRepository.findByEmail(unrelatedSession.user().email()).orElseThrow();
        TransferResponse transfer = walletService.transfer(sender.getId(), UUID.randomUUID(),
            new TransferRequest(recipientEmail, BigDecimal.ONE, PASSWORD, null));
        String body = """
            {"reason":"Autorización de soporte","password":"%s"}
            """.formatted(PASSWORD).trim();

        mockMvc.perform(post("/api/transfers/{id}/reversal", transfer.transactionId())
                .header("Authorization", "Bearer " + senderSession.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());

        grantReversalPermission(operator);
        mockMvc.perform(get("/api/me")
                .header("Authorization", "Bearer " + operatorSession.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canReverseTransfers").value(true));
        var result = mockMvc.perform(post("/api/transfers/{id}/reversal", transfer.transactionId())
                .header("Authorization", "Bearer " + operatorSession.accessToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.operationType").value("REVERSAL"))
            .andExpect(jsonPath("$.originalTransactionId").value(
                transfer.transactionId().toString()))
            .andExpect(jsonPath("$.reason").value("Autorización de soporte"))
            .andReturn();
        String reversalId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(result.getResponse().getContentAsString())
            .get("transactionId").asText();

        mockMvc.perform(get("/api/transfers/{id}", reversalId)
                .header("Authorization", "Bearer " + unrelatedSession.accessToken()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/transfers/{id}", reversalId)
                .header("Authorization", "Bearer " + recipientSession.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operationType").value("REVERSAL"));
        assertNotEquals(sender.getId(), unrelated.getId());
        assertEquals(operator.getId(), jdbcTemplate.queryForObject("""
            SELECT authorized_by_user_id FROM transfer_reversal_audits
            WHERE reversal_transaction_id = ?
            """, UUID.class, UUID.fromString(reversalId)));
    }

    private AppUser register(String name) {
        AppUser user = registerUnverified(name);
        verifyEmail(user);
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private AppUser registerUnverified(String name) {
        String email = name.toLowerCase().replaceAll("[^a-z0-9]+", ".")
            + "." + UUID.randomUUID() + "@example.test";
        authService.register(new RegisterRequest(name, email, PASSWORD));
        return userRepository.findByEmail(email).orElseThrow();
    }

    private void verifyEmail(AppUser user) {
        emailVerificationService.confirm(verificationToken(user));
    }

    private String verificationToken(AppUser user) {
        byte[] ciphertext = jdbcTemplate.queryForObject("""
            SELECT o.encrypted_token
            FROM auth_email_verification_outbox o
            WHERE o.user_id = ? AND o.status = 'PENDING'
            ORDER BY o.created_at DESC LIMIT 1
            """, byte[].class, user.getId());
        return secretCipher.decrypt(user.getId().toString(), ciphertext);
    }

    private long walletCount(UUID userId) {
        return jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_accounts
            WHERE owner_user_id = ? AND account_type = 'WALLET'
            """, Long.class, userId);
    }

    private int countEntriesForTransferAttempt(UUID actorUserId, UUID idempotencyKey) {
        return jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_entries e
            JOIN ledger_transactions t ON t.id = e.transaction_id
            WHERE t.actor_user_id = ? AND t.idempotency_key = ?
            """, Integer.class, actorUserId, idempotencyKey.toString());
    }

    private AppUser authorizeReverser(String name) {
        AppUser user = register(name);
        grantReversalPermission(user);
        return user;
    }

    private void grantReversalPermission(AppUser user) {
        jdbcTemplate.update("""
            INSERT INTO app_user_permissions
                (user_id, permission_code, granted_by_user_id)
            VALUES (?, 'TRANSFER_REVERSAL', ?)
            """, user.getId(), user.getId());
    }

    private String reverseAttempt(
        CountDownLatch start, AppUser operator, TransferResponse transfer, UUID key
    ) throws InterruptedException {
        start.await();
        try {
            reversalService.reverse(operator.getId(), transfer.transactionId(), key,
                new ReversalRequest("Reversión concurrente", PASSWORD, null));
            return "COMPLETED";
        } catch (DomainException exception) {
            return exception.getCode();
        }
    }

    private Boolean overspendAttempt(CountDownLatch start, AppUser sender, AppUser recipient)
        throws InterruptedException {
        start.await();
        try {
            walletService.transfer(sender.getId(), UUID.randomUUID(),
                new TransferRequest(recipient.getEmail(), new BigDecimal("80.00"), PASSWORD, null));
            return true;
        } catch (DomainException exception) {
            assertEquals("INSUFFICIENT_FUNDS", exception.getCode());
            return false;
        }
    }

    private List<TransferResponse> executeConcurrently(List<Callable<TransferResponse>> calls)
        throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<TransferResponse>> futures = calls.stream().map(call -> executor.submit(() -> {
                start.await();
                return call.call();
            })).toList();
            start.countDown();
            List<TransferResponse> results = new ArrayList<>();
            for (Future<TransferResponse> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private BigDecimal balance(AppUser user) {
        var wallet = accountRepository.findByOwnerUserIdAndCurrency(user.getId(), "GTQ")
            .orElseThrow();
        return entryRepository.findBalance(wallet.getId());
    }

    private void assertBalance(AppUser user, String expected) {
        assertEquals(0, balance(user).compareTo(new BigDecimal(expected)));
    }

    private int transferCount(UUID actorId, UUID idempotencyKey) {
        return jdbcTemplate.queryForObject("""
            SELECT count(*) FROM ledger_transactions
            WHERE actor_user_id = ? AND idempotency_key = ? AND type = 'TRANSFER'
            """, Integer.class, actorId, idempotencyKey.toString());
    }

    private int reversalAuditCount(UUID originalTransactionId) {
        return jdbcTemplate.queryForObject("""
            SELECT count(*) FROM transfer_reversal_audits
            WHERE original_transaction_id = ?
            """, Integer.class, originalTransactionId);
    }

    private int countEntries(UUID transactionId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
            Integer.class, transactionId);
    }

    private void postponeAllPendingDeliveries() {
        jdbcTemplate.update("""
            UPDATE auth_email_verification_outbox
            SET next_attempt_at = ?
            WHERE status = 'PENDING'
            """, java.sql.Timestamp.from(Instant.now().plusSeconds(86_400)));
        jdbcTemplate.update("""
            UPDATE auth_email_verification_outbox
            SET lease_until = ?
            WHERE status = 'PROCESSING'
            """, java.sql.Timestamp.from(Instant.now().plusSeconds(86_400)));
    }

    private int countUnbalancedTransactions() {
        return jdbcTemplate.queryForObject("""
            SELECT count(*) FROM (
                SELECT transaction_id
                FROM ledger_entries
                GROUP BY transaction_id
                HAVING coalesce(sum(amount) FILTER (WHERE entry_type = 'DEBIT'), 0)
                    <> coalesce(sum(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
            ) unbalanced
            """, Integer.class);
    }

    private int auditEventCount(UUID actorId, UUID key) {
        String hash = com.cobalto.api.security.TokenHash.sha256(key.toString());
        return jdbcTemplate.queryForObject("""
            SELECT count(*) FROM transfer_audit_events
            WHERE actor_user_id = ? AND idempotency_key_hash = ?
            """, Integer.class, actorId, hash);
    }

    private void installNotificationFailureTrigger() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE FUNCTION reject_test_transfer_notification() RETURNS TRIGGER AS $$
                BEGIN
                    IF NEW.notification_type IN ('TRANSFER_RECEIVED', 'TRANSFER_REVERSED') THEN
                        RAISE EXCEPTION 'injected notification failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
            statement.execute("""
                CREATE TRIGGER tr_test_transfer_notification_failure
                BEFORE INSERT ON app_notifications
                FOR EACH ROW EXECUTE FUNCTION reject_test_transfer_notification()
                """);
        }
    }

    private void dropNotificationFailureTrigger() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS tr_test_transfer_notification_failure"
                + " ON app_notifications");
            statement.execute("DROP FUNCTION IF EXISTS reject_test_transfer_notification()");
        }
    }

    private static String mask(String email) {
        int separator = email.indexOf('@');
        return email.substring(0, 1) + "***" + email.substring(separator);
    }

    private static synchronized EmbeddedPostgres startPostgres() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException exception) {
                throw new IllegalStateException("No se pudo iniciar PostgreSQL de integración", exception);
            }
        }
        return postgres;
    }
}
