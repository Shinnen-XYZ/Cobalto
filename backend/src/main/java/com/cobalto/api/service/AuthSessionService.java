package com.cobalto.api.service;

import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.RefreshSession;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.RefreshSessionRepository;
import com.cobalto.api.security.JwtService;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!migrate")
public class AuthSessionService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshSessionRepository sessionRepository;
    private final AppUserRepository userRepository;
    private final JwtService jwtService;
    private final long refreshTokenDays;

    public AuthSessionService(RefreshSessionRepository sessionRepository,
                              AppUserRepository userRepository, JwtService jwtService,
                              @Value("${cobalto.security.refresh-token-days:30}")
                              long refreshTokenDays) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.refreshTokenDays = refreshTokenDays;
        if (refreshTokenDays < 1 || refreshTokenDays > 90) {
            throw new IllegalArgumentException("Refresh-token lifetime must be between 1 and 90 days");
        }
    }

    @Transactional
    public SessionTokens create(AppUser user) {
        Instant now = Instant.now();
        UUID familyId = UUID.randomUUID();
        String token = newRawToken();
        sessionRepository.save(new RefreshSession(UUID.randomUUID(), user.getId(), familyId,
            hash(token), now, now.plus(refreshTokenDays, ChronoUnit.DAYS)));
        return tokens(user, token);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public SessionTokens rotate(String rawToken) {
        Instant now = Instant.now();
        RefreshSession current = sessionRepository.findLockedByTokenHash(hash(rawToken))
            .orElseThrow(AuthSessionService::invalidRefreshToken);

        if (current.getRevokedAt() != null) {
            sessionRepository.revokeActiveFamily(current.getFamilyId(), now);
            throw invalidRefreshToken();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            current.revoke(now, null);
            sessionRepository.save(current);
            throw invalidRefreshToken();
        }

        AppUser user = userRepository.findById(current.getUserId())
            .orElseThrow(AuthSessionService::invalidRefreshToken);
        String replacementToken = newRawToken();
        UUID replacementId = UUID.randomUUID();
        sessionRepository.save(new RefreshSession(replacementId, user.getId(), current.getFamilyId(),
            hash(replacementToken), now, now.plus(refreshTokenDays, ChronoUnit.DAYS)));
        current.revoke(now, replacementId);
        sessionRepository.save(current);
        return tokens(user, replacementToken);
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        sessionRepository.findLockedByTokenHash(hash(rawToken)).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.revoke(Instant.now(), null);
                sessionRepository.save(session);
            }
        });
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        sessionRepository.revokeAllForUser(userId, Instant.now());
    }

    private SessionTokens tokens(AppUser user, String refreshToken) {
        return new SessionTokens(user, jwtService.issueToken(user), refreshToken,
            jwtService.getTokenLifetimeSeconds());
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        return com.cobalto.api.security.TokenHash.sha256(token);
    }

    private static DomainException invalidRefreshToken() {
        return new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
            "La sesión expiró o ya no es válida");
    }

    public record SessionTokens(AppUser user, String accessToken, String refreshToken,
                                long expiresInSeconds) {}
}
