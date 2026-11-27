package com.cobalto.api.service;

import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.EmailVerificationOutbox;
import com.cobalto.api.domain.EmailVerificationToken;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.EmailVerificationOutboxRepository;
import com.cobalto.api.repository.EmailVerificationTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!migrate")
public class EmailVerificationOutboxCoordinator {
    private static final int MAX_ATTEMPTS = 5;

    private final EmailVerificationOutboxRepository outboxRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final AppUserRepository userRepository;
    private final Duration leaseDuration;

    public EmailVerificationOutboxCoordinator(
        EmailVerificationOutboxRepository outboxRepository,
        EmailVerificationTokenRepository tokenRepository,
        AppUserRepository userRepository,
        @Value("${cobalto.email-verification.lease-duration:PT5M}")
        Duration leaseDuration
    ) {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Email outbox lease must be positive");
        }
        this.outboxRepository = outboxRepository;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.leaseDuration = leaseDuration;
    }

    @Transactional
    public List<Claim> claimDue(Instant now, int batchSize) {
        failExhaustedDue(now);
        List<EmailVerificationOutbox> due =
            outboxRepository.findClaimableForUpdate(now, batchSize, MAX_ATTEMPTS);
        List<Claim> claims = new ArrayList<>();
        for (EmailVerificationOutbox item : due) {
            if ("PROCESSING".equals(item.getStatus())) {
                item.recoverExpiredLease(now.plusSeconds(backoffSeconds(item.getAttempts())));
                continue;
            }
            UUID leaseToken = UUID.randomUUID();
            item.claim(leaseToken, now.plus(leaseDuration));
            claims.add(new Claim(
                item.getId(), leaseToken, item.getUserId(), item.getTokenId(), item.getAttempts()));
        }
        return List.copyOf(claims);
    }

    @Transactional(readOnly = true)
    public Optional<Delivery> prepareDelivery(Claim claim, Instant now) {
        return loadDelivery(claim, now);
    }

    @Transactional
    public boolean renewLeaseForSend(Claim claim, Delivery prepared, Instant now) {
        return outboxRepository.renewLeaseForSend(
            claim.id(), claim.leaseToken(), prepared.userId(), prepared.tokenId(),
            prepared.recipientEmail(), prepared.tokenHash(), prepared.encryptedToken(),
            now, now.plus(leaseDuration)) == 1;
    }

    private Optional<Delivery> loadDelivery(Claim claim, Instant now) {
        EmailVerificationOutbox item = outboxRepository
            .findByIdAndStatusAndLeaseToken(claim.id(), "PROCESSING", claim.leaseToken())
            .orElse(null);
        if (item == null || !item.getTokenId().equals(claim.tokenId())
            || item.getLeaseUntil() == null || !item.getLeaseUntil().isAfter(now)
            || item.getEncryptedToken() == null) {
            return Optional.empty();
        }

        AppUser user = userRepository.findById(item.getUserId()).orElse(null);
        EmailVerificationToken token =
            tokenRepository.findById(item.getTokenId()).orElse(null);
        if (user == null || token == null || user.isEmailVerified()
            || !item.getRecipientEmail().equals(user.getEmail())
            || !item.getUserId().equals(token.getUserId())
            || token.getConsumedAt() != null || token.getRevokedAt() != null
            || !token.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        return Optional.of(new Delivery(
            item.getId(), claim.leaseToken(), item.getUserId(), item.getTokenId(),
            item.getRecipientEmail(), token.getTokenHash(), item.getEncryptedToken()));
    }

    @Transactional
    public void cancelClaim(Claim claim, Instant now) {
        lockUserAndToken(claim);
        if (outboxRepository.cancelClaim(claim.id(), claim.leaseToken()) != 0) {
            tokenRepository.revokeById(claim.tokenId(), now);
        }
    }

    @Transactional
    public void markSent(Claim claim, Instant now) {
        outboxRepository.markSent(claim.id(), claim.leaseToken(), now);
    }

    @Transactional
    public boolean recordFailure(
        Claim claim, Instant nextAttemptAt, String errorCode, Instant now
    ) {
        boolean terminal = claim.attempts() >= MAX_ATTEMPTS;
        if (terminal) {
            lockUserAndToken(claim);
        }
        int updated = outboxRepository.recordFailure(
            claim.id(), claim.leaseToken(), nextAttemptAt, errorCode, MAX_ATTEMPTS);
        if (updated != 0 && terminal) {
            tokenRepository.revokeById(claim.tokenId(), now);
        }
        return updated != 0 && terminal;
    }

    private void failExhaustedDue(Instant now) {
        for (EmailVerificationOutbox item
            : outboxRepository.findExhaustedDue(now, 100, MAX_ATTEMPTS)) {
            lockUserAndToken(item.getUserId(), item.getTokenId());
            String errorCode = "PROCESSING".equals(item.getStatus())
                ? "LEASE_EXPIRED" : "ATTEMPT_LIMIT";
            if (outboxRepository.failExhaustedDue(
                    item.getId(), now, errorCode, MAX_ATTEMPTS) != 0) {
                tokenRepository.revokeById(item.getTokenId(), now);
            }
        }
    }

    private void lockUserAndToken(Claim claim) {
        lockUserAndToken(claim.userId(), claim.tokenId());
    }

    private void lockUserAndToken(UUID userId, UUID tokenId) {
        userRepository.findLockedById(userId)
            .orElseThrow(() -> new IllegalStateException(
                "Email verification outbox owner is missing"));
        EmailVerificationToken token = tokenRepository.findLockedById(tokenId)
            .orElseThrow(() -> new IllegalStateException(
                "Email verification outbox token is missing"));
        if (!userId.equals(token.getUserId())) {
            throw new IllegalStateException(
                "Email verification outbox token belongs to a different user");
        }
    }

    private static long backoffSeconds(int attempts) {
        return Math.min(3600, 30L << Math.min(Math.max(attempts - 1, 0), 7));
    }

    public record Claim(UUID id, UUID leaseToken, UUID userId, UUID tokenId, short attempts) {}

    public record Delivery(
        UUID id,
        UUID leaseToken,
        UUID userId,
        UUID tokenId,
        String recipientEmail,
        String tokenHash,
        byte[] encryptedToken
    ) {}
}
