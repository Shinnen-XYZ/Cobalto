package com.cobalto.api.service;

import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.EmailVerificationOutbox;
import com.cobalto.api.domain.EmailVerificationToken;
import com.cobalto.api.dto.EmailVerificationResponse;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.EmailVerificationOutboxRepository;
import com.cobalto.api.repository.EmailVerificationTokenRepository;
import com.cobalto.api.security.MfaSecretCipher;
import com.cobalto.api.security.TokenHash;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Profile("!migrate")
public class EmailVerificationService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final AppUserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailVerificationOutboxRepository outboxRepository;
    private final MfaSecretCipher secretCipher;
    private final JdbcTemplate jdbcTemplate;
    private final Duration tokenLifetime;
    private final Duration resendCooldown;
    private final int maxRequestsPerDay;

    public EmailVerificationService(
        AppUserRepository userRepository,
        EmailVerificationTokenRepository tokenRepository,
        EmailVerificationOutboxRepository outboxRepository,
        MfaSecretCipher secretCipher,
        JdbcTemplate jdbcTemplate,
        @Value("${cobalto.email-verification.token-lifetime:PT30M}") Duration tokenLifetime,
        @Value("${cobalto.email-verification.resend-cooldown:PT1M}") Duration resendCooldown,
        @Value("${cobalto.email-verification.max-requests-per-day:6}") int maxRequestsPerDay
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.outboxRepository = outboxRepository;
        this.secretCipher = secretCipher;
        this.jdbcTemplate = jdbcTemplate;
        this.tokenLifetime = tokenLifetime;
        this.resendCooldown = resendCooldown;
        this.maxRequestsPerDay = maxRequestsPerDay;
    }

    @Transactional
    public void issue(AppUser user) {
        enqueue(user, Instant.now());
    }

    @Transactional
    public EmailVerificationResponse resend(UUID userId) {
        AppUser user = userRepository.findLockedById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        if (user.isEmailVerified()) {
            return new EmailVerificationResponse(true, "El correo ya está verificado.");
        }

        Instant now = Instant.now();
        EmailVerificationToken latest =
            tokenRepository.findTopByUserIdOrderByCreatedAtDesc(userId).orElse(null);
        if (latest != null && latest.getCreatedAt().plus(resendCooldown).isAfter(now)) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS,
                "EMAIL_VERIFICATION_COOLDOWN",
                "Espera antes de solicitar otro correo de verificación");
        }
        if (tokenRepository.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofDays(1)))
            >= maxRequestsPerDay) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS,
                "EMAIL_VERIFICATION_LIMIT",
                "Se alcanzó el límite diario de correos de verificación");
        }

        enqueue(user, now);
        return new EmailVerificationResponse(
            false, "Si tu cuenta requiere verificación, enviaremos un correo.");
    }

    @Transactional
    public void confirm(String token) {
        UUID userId = jdbcTemplate.queryForObject(
            "SELECT consume_email_verification_token(?)",
            (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
            TokenHash.sha256(token));
        if (userId == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST,
                "INVALID_EMAIL_VERIFICATION_TOKEN",
                "El enlace de verificación no es válido o ha expirado");
        }
    }

    private void enqueue(AppUser user, Instant now) {
        tokenRepository.revokeActiveForUser(user.getId(), now);
        outboxRepository.cancelPendingForUser(user.getId());

        String token = newOpaqueToken();
        UUID tokenId = UUID.randomUUID();
        tokenRepository.save(new EmailVerificationToken(
            tokenId, user.getId(), TokenHash.sha256(token), now, now.plus(tokenLifetime)));
        outboxRepository.save(new EmailVerificationOutbox(
            UUID.randomUUID(), user.getId(), tokenId, user.getEmail(),
            secretCipher.encrypt(user.getId().toString(), token), now));
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
