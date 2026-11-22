package com.cobalto.api.service;

import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.MfaLoginChallenge;
import com.cobalto.api.domain.MfaRecoveryCode;
import com.cobalto.api.dto.MfaSetupResponse;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.MfaLoginChallengeRepository;
import com.cobalto.api.repository.MfaRecoveryCodeRepository;
import com.cobalto.api.security.MfaSecretCipher;
import com.cobalto.api.security.TokenHash;
import com.cobalto.api.security.TotpService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!migrate")
public class MfaService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int MAX_CHALLENGE_ATTEMPTS = 5;

    private final AppUserRepository userRepository;
    private final MfaLoginChallengeRepository challengeRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final MfaSecretCipher secretCipher;
    private final TotpService totpService;

    public MfaService(AppUserRepository userRepository,
                      MfaLoginChallengeRepository challengeRepository,
                      MfaRecoveryCodeRepository recoveryCodeRepository,
                      MfaSecretCipher secretCipher, TotpService totpService) {
        this.userRepository = userRepository;
        this.challengeRepository = challengeRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.secretCipher = secretCipher;
        this.totpService = totpService;
    }

    @Transactional
    public String createLoginChallenge(AppUser user) {
        Instant now = Instant.now();
        user.clearExpiredMfaLock(now);
        if (user.isMfaLocked(now)) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "MFA_TEMPORARILY_LOCKED",
                "La verificación está temporalmente bloqueada");
        }
        challengeRepository.consumePendingForUser(user.getId(), now);
        String challenge = newOpaqueToken();
        challengeRepository.save(new MfaLoginChallenge(UUID.randomUUID(), user.getId(),
            TokenHash.sha256(challenge), now, now.plus(5, ChronoUnit.MINUTES)));
        return challenge;
    }

    @Transactional(noRollbackFor = DomainException.class)
    public AppUser verifyLoginChallenge(String challenge, String code, String recoveryCode) {
        Instant now = Instant.now();
        MfaLoginChallenge loginChallenge = challengeRepository
            .findLockedByTokenHash(TokenHash.sha256(challenge))
            .orElseThrow(MfaService::invalidChallenge);
        if (loginChallenge.getConsumedAt() != null
            || !loginChallenge.getExpiresAt().isAfter(now)) {
            throw invalidChallenge();
        }

        AppUser user = userRepository.findLockedById(loginChallenge.getUserId())
            .orElseThrow(MfaService::invalidChallenge);
        if (user.isMfaLocked(now)) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "MFA_TEMPORARILY_LOCKED",
                "La verificación está temporalmente bloqueada");
        }
        if (!consumeFactor(user, code, recoveryCode, now)) {
            loginChallenge.fail(now);
            user.recordMfaFailure(now);
            if (loginChallenge.getFailedAttempts() >= MAX_CHALLENGE_ATTEMPTS) {
                challengeRepository.save(loginChallenge);
                userRepository.save(user);
                throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "MFA_ATTEMPTS_EXCEEDED",
                    "Se agotaron los intentos; inicia sesión nuevamente");
            }
            challengeRepository.save(loginChallenge);
            userRepository.save(user);
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE",
                "El código no es válido");
        }

        user.clearMfaFailures();
        loginChallenge.consume(now);
        challengeRepository.save(loginChallenge);
        return user;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = DomainException.class)
    public void verifySensitiveAction(UUID userId, String factor) {
        AppUser user = userRepository.findLockedById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        Instant now = Instant.now();
        user.clearExpiredMfaLock(now);
        if (!user.isMfaEnabled()) {
            throw new DomainException(HttpStatus.CONFLICT, "MFA_NOT_ENABLED",
                "La configuración multifactor cambió; inicia sesión nuevamente");
        }
        if (user.isMfaLocked(now)) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "MFA_TEMPORARILY_LOCKED",
                "La verificación está temporalmente bloqueada");
        }

        String code = factor != null && factor.matches("\\d{6}") ? factor : null;
        String recoveryCode = code == null ? factor : null;
        if (!consumeFactor(user, code, recoveryCode, now)) {
            user.recordMfaFailure(now);
            userRepository.save(user);
            if (user.isMfaLocked(now)) {
                throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "MFA_ATTEMPTS_EXCEEDED",
                    "Se agotaron los intentos; inténtalo más tarde");
            }
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE",
                "El código no es válido");
        }
        user.clearMfaFailures();
        userRepository.save(user);
    }

    @Transactional
    public MfaSetupResponse beginEnrollment(UUID userId) {
        AppUser user = userRepository.findLockedById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        user.clearExpiredMfaLock(Instant.now());
        if (user.isMfaEnabled()) {
            throw new DomainException(HttpStatus.CONFLICT, "MFA_ALREADY_ENABLED",
                "La autenticación multifactor ya está activada");
        }

        String secret = totpService.newSecret();
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
        user.setPendingMfaSecret(
            secretCipher.encrypt(userId.toString(), secret), expiresAt);
        return new MfaSetupResponse(secret, totpService.otpAuthUri(user.getEmail(), secret), expiresAt);
    }

    @Transactional(noRollbackFor = DomainException.class)
    public List<String> confirmEnrollment(UUID userId, String code) {
        AppUser user = userRepository.findLockedById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        Instant now = Instant.now();
        user.clearExpiredMfaLock(now);
        if (user.isMfaLocked(now)) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "MFA_TEMPORARILY_LOCKED",
                "La verificación está temporalmente bloqueada");
        }
        byte[] pendingSecret = user.getMfaPendingSecretEncrypted();
        if (user.isMfaEnabled() || pendingSecret == null
            || user.getMfaPendingExpiresAt() == null
            || !user.getMfaPendingExpiresAt().isAfter(now)) {
            throw new DomainException(HttpStatus.CONFLICT, "MFA_ENROLLMENT_EXPIRED",
                "Inicia nuevamente la configuración de MFA");
        }

        String secret = secretCipher.decrypt(userId.toString(), pendingSecret);
        OptionalLong matchedCounter = totpService.matchingCounter(secret, code, now);
        if (matchedCounter.isEmpty()) {
            user.recordMfaFailure(now);
            userRepository.save(user);
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE",
                "El código no es válido");
        }

        user.clearMfaFailures();
        user.completeMfaEnrollment(
            pendingSecret, matchedCounter.getAsLong());
        recoveryCodeRepository.deleteAllByUserId(userId);
        List<String> recoveryCodes = generateRecoveryCodes(userId, now);
        return recoveryCodes;
    }

    @Transactional(noRollbackFor = DomainException.class)
    public void disable(UUID userId, String code, String recoveryCode) {
        AppUser user = userRepository.findLockedById(userId)
            .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "No se encontró el usuario"));
        Instant now = Instant.now();
        user.clearExpiredMfaLock(now);
        if (!user.isMfaEnabled()) {
            throw new DomainException(HttpStatus.CONFLICT, "MFA_NOT_ENABLED",
                "La autenticación multifactor no está activada");
        }
        if (user.isMfaLocked(now)) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "MFA_TEMPORARILY_LOCKED",
                "La verificación está temporalmente bloqueada");
        }
        if (!consumeFactor(user, code, recoveryCode, now)) {
            user.recordMfaFailure(now);
            userRepository.save(user);
            if (user.isMfaLocked(now)) {
                throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "MFA_ATTEMPTS_EXCEEDED",
                    "Se agotaron los intentos; inténtalo más tarde");
            }
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE",
                "El código no es válido");
        }
        user.clearMfaFailures();
        user.disableMfa();
        recoveryCodeRepository.deleteAllByUserId(userId);
        challengeRepository.consumePendingForUser(userId, now);
    }

    private boolean consumeFactor(AppUser user, String code, String recoveryCode, Instant now) {
        boolean hasTotpCode = code != null && !code.isBlank();
        boolean hasRecoveryCode = recoveryCode != null && !recoveryCode.isBlank();
        if (hasTotpCode == hasRecoveryCode || !user.isMfaEnabled()) return false;

        if (hasTotpCode) {
            if (user.getMfaSecretEncrypted() == null) return false;
            String secret = secretCipher.decrypt(user.getId().toString(), user.getMfaSecretEncrypted());
            OptionalLong counter = totpService.matchingCounter(secret, code, now);
            if (counter.isEmpty() || counter.getAsLong() <= user.getMfaLastCounter()) {
                return false;
            }
            user.updateMfaCounter(counter.getAsLong());
            return true;
        }

        String normalizedCode = normalizeRecoveryCode(recoveryCode);
        if (normalizedCode == null) return false;
        MfaRecoveryCode storedCode = recoveryCodeRepository
            .findLockedByUserIdAndCodeHash(user.getId(), TokenHash.sha256(normalizedCode))
            .orElse(null);
        if (storedCode == null || storedCode.getUsedAt() != null) return false;
        storedCode.consume(now);
        recoveryCodeRepository.save(storedCode);
        return true;
    }

    private List<String> generateRecoveryCodes(UUID userId, Instant now) {
        List<String> recoveryCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int index = 0; index < RECOVERY_CODE_COUNT; index++) {
            byte[] random = new byte[16];
            SECURE_RANDOM.nextBytes(random);
            String code = HexFormat.of().formatHex(random).toUpperCase(java.util.Locale.ROOT);
            String formatted = code.replaceAll("(.{4})(?!$)", "$1-");
            recoveryCodeRepository.save(new MfaRecoveryCode(UUID.randomUUID(), userId,
                TokenHash.sha256(code), now));
            recoveryCodes.add(formatted);
        }
        return List.copyOf(recoveryCodes);
    }

    private static String normalizeRecoveryCode(String code) {
        String normalized = code.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{32}")) return null;
        return normalized;
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static DomainException invalidChallenge() {
        return new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CHALLENGE",
            "La verificación expiró o ya no es válida");
    }
}
