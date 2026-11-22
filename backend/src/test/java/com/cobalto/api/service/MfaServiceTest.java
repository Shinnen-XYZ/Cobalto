package com.cobalto.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.MfaLoginChallenge;
import com.cobalto.api.domain.MfaRecoveryCode;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.MfaLoginChallengeRepository;
import com.cobalto.api.repository.MfaRecoveryCodeRepository;
import com.cobalto.api.security.MfaSecretCipher;
import com.cobalto.api.security.TokenHash;
import com.cobalto.api.security.TotpService;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String RAW_CHALLENGE = "one-time-challenge";

    @Mock private AppUserRepository userRepository;
    @Mock private MfaLoginChallengeRepository challengeRepository;
    @Mock private MfaRecoveryCodeRepository recoveryCodeRepository;
    @Mock private AppUser user;

    @Test
    void failedCodesPersistAttemptsAndConsumeChallengeAfterFiveFailures() {
        Instant now = Instant.now();
        MfaLoginChallenge challenge = new MfaLoginChallenge(UUID.randomUUID(), USER_ID,
            TokenHash.sha256(RAW_CHALLENGE), now.minusSeconds(1), now.plusSeconds(300));
        when(challengeRepository.findLockedByTokenHash(TokenHash.sha256(RAW_CHALLENGE)))
            .thenReturn(Optional.of(challenge));
        when(userRepository.findLockedById(USER_ID)).thenReturn(Optional.of(user));
        when(user.isMfaEnabled()).thenReturn(false);
        MfaService service = new MfaService(userRepository, challengeRepository,
            recoveryCodeRepository,
            new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[32])),
            new TotpService());

        for (int attempt = 1; attempt <= 4; attempt++) {
            DomainException exception = assertThrows(DomainException.class,
                () -> service.verifyLoginChallenge(RAW_CHALLENGE, "123456", null));
            assertEquals("INVALID_MFA_CODE", exception.getCode());
        }
        DomainException locked = assertThrows(DomainException.class,
            () -> service.verifyLoginChallenge(RAW_CHALLENGE, "123456", null));
        assertEquals("MFA_ATTEMPTS_EXCEEDED", locked.getCode());
        assertEquals(5, challenge.getFailedAttempts());
        assertNotNull(challenge.getConsumedAt());
        verify(challengeRepository, org.mockito.Mockito.times(5)).save(challenge);

        DomainException replay = assertThrows(DomainException.class,
            () -> service.verifyLoginChallenge(RAW_CHALLENGE, "123456", null));
        assertEquals("INVALID_MFA_CHALLENGE", replay.getCode());
    }

    @Test
    void recoveryCodeIsAcceptedOnceAndCannotBeReused() {
        String formattedRecoveryCode = "0123-4567-89AB-CDEF-0123-4567-89AB-CDEF";
        String recoveryHash = TokenHash.sha256(
            "0123456789ABCDEF0123456789ABCDEF");
        MfaRecoveryCode recoveryCode =
            new MfaRecoveryCode(UUID.randomUUID(), USER_ID, recoveryHash, Instant.now());
        MfaLoginChallenge firstChallenge = newChallenge("first-challenge");
        MfaLoginChallenge secondChallenge = newChallenge("second-challenge");
        when(challengeRepository.findLockedByTokenHash(
            TokenHash.sha256("first-challenge"))).thenReturn(Optional.of(firstChallenge));
        when(challengeRepository.findLockedByTokenHash(
            TokenHash.sha256("second-challenge"))).thenReturn(Optional.of(secondChallenge));
        when(userRepository.findLockedById(USER_ID))
            .thenReturn(Optional.of(user), Optional.of(user));
        when(user.getId()).thenReturn(USER_ID);
        when(user.isMfaEnabled()).thenReturn(true);
        when(recoveryCodeRepository.findLockedByUserIdAndCodeHash(USER_ID, recoveryHash))
            .thenReturn(Optional.of(recoveryCode), Optional.of(recoveryCode));
        MfaService service = new MfaService(userRepository, challengeRepository,
            recoveryCodeRepository,
            new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[32])),
            new TotpService());

        assertEquals(user, service.verifyLoginChallenge(
            "first-challenge", null, formattedRecoveryCode));
        assertNotNull(recoveryCode.getUsedAt());
        assertNotNull(firstChallenge.getConsumedAt());
        verify(recoveryCodeRepository).save(recoveryCode);

        DomainException reused = assertThrows(DomainException.class,
            () -> service.verifyLoginChallenge(
                "second-challenge", null, formattedRecoveryCode));
        assertEquals("INVALID_MFA_CODE", reused.getCode());
        assertTrue(secondChallenge.getFailedAttempts() > 0);
        verify(recoveryCodeRepository).save(recoveryCode);
    }

    @Test
    void transferStepUpConsumesRecoveryCodeOnce() {
        String recoveryCodeValue = "89ABCDEF0123456789ABCDEF01234567";
        String recoveryHash = TokenHash.sha256(recoveryCodeValue);
        MfaRecoveryCode recoveryCode =
            new MfaRecoveryCode(UUID.randomUUID(), USER_ID, recoveryHash, Instant.now());
        when(userRepository.findLockedById(USER_ID)).thenReturn(Optional.of(user), Optional.of(user));
        when(user.getId()).thenReturn(USER_ID);
        when(user.isMfaEnabled()).thenReturn(true);
        when(recoveryCodeRepository.findLockedByUserIdAndCodeHash(USER_ID, recoveryHash))
            .thenReturn(Optional.of(recoveryCode), Optional.of(recoveryCode));
        MfaService service = new MfaService(userRepository, challengeRepository,
            recoveryCodeRepository,
            new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[32])),
            new TotpService());

        service.verifySensitiveAction(USER_ID, recoveryCodeValue);
        assertNotNull(recoveryCode.getUsedAt());
        verify(recoveryCodeRepository).save(recoveryCode);

        DomainException reused = assertThrows(DomainException.class,
            () -> service.verifySensitiveAction(USER_ID, recoveryCodeValue));
        assertEquals("INVALID_MFA_CODE", reused.getCode());
    }

    private static MfaLoginChallenge newChallenge(String token) {
        Instant now = Instant.now();
        return new MfaLoginChallenge(UUID.randomUUID(), USER_ID,
            TokenHash.sha256(token), now, now.plusSeconds(300));
    }
}
