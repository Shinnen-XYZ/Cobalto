package com.cobalto.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.cobalto.api.domain.AppUser;
import com.cobalto.api.domain.RefreshSession;
import com.cobalto.api.exception.DomainException;
import com.cobalto.api.repository.AppUserRepository;
import com.cobalto.api.repository.RefreshSessionRepository;
import com.cobalto.api.security.JwtService;
import com.cobalto.api.security.TokenHash;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID FAMILY_ID = UUID.randomUUID();
    private static final String OLD_TOKEN = "old-refresh-token";

    @Mock private RefreshSessionRepository sessionRepository;
    @Mock private AppUserRepository userRepository;
    @Mock private JwtService jwtService;
    @Mock private AppUser user;

    private AuthSessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new AuthSessionService(sessionRepository, userRepository, jwtService, 30);
        lenient().when(user.getId()).thenReturn(USER_ID);
        lenient().when(userRepository.findById(USER_ID)).thenReturn(java.util.Optional.of(user));
        lenient().when(jwtService.issueToken(user)).thenReturn("new-access-token");
        lenient().when(jwtService.getTokenLifetimeSeconds()).thenReturn(900L);
    }

    @Test
    void rotatesRefreshTokenAndRevokesThePreviousToken() {
        RefreshSession previous = new RefreshSession(UUID.randomUUID(), USER_ID, FAMILY_ID,
            TokenHash.sha256(OLD_TOKEN), Instant.now().minusSeconds(10),
            Instant.now().plusSeconds(3600));
        when(sessionRepository.findLockedByTokenHash(TokenHash.sha256(OLD_TOKEN)))
            .thenReturn(java.util.Optional.of(previous));

        AuthSessionService.SessionTokens tokens = sessionService.rotate(OLD_TOKEN);

        assertEquals("new-access-token", tokens.accessToken());
        assertNotEquals(OLD_TOKEN, tokens.refreshToken());
        assertEquals(43, tokens.refreshToken().length());
        assertNotNull(previous.getRevokedAt());
        ArgumentCaptor<RefreshSession> saved = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessionRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals(TokenHash.sha256(tokens.refreshToken()), saved.getAllValues().get(0).getTokenHash());
        assertEquals(FAMILY_ID, saved.getAllValues().get(0).getFamilyId());
    }

    @Test
    void reuseOfRotatedTokenRevokesItsEntireFamily() {
        RefreshSession reused = new RefreshSession(UUID.randomUUID(), USER_ID, FAMILY_ID,
            TokenHash.sha256(OLD_TOKEN), Instant.now().minusSeconds(20),
            Instant.now().plusSeconds(3600));
        reused.revoke(Instant.now().minusSeconds(5), null);
        when(sessionRepository.findLockedByTokenHash(TokenHash.sha256(OLD_TOKEN)))
            .thenReturn(java.util.Optional.of(reused));

        assertThrows(DomainException.class, () -> sessionService.rotate(OLD_TOKEN));

        verify(sessionRepository).revokeActiveFamily(org.mockito.ArgumentMatchers.eq(FAMILY_ID),
            any(Instant.class));
    }

    @Test
    void expiredRefreshTokenIsRevokedAndRejected() {
        RefreshSession expired = new RefreshSession(UUID.randomUUID(), USER_ID, FAMILY_ID,
            TokenHash.sha256(OLD_TOKEN), Instant.now().minusSeconds(7200),
            Instant.now().minusSeconds(3600));
        when(sessionRepository.findLockedByTokenHash(TokenHash.sha256(OLD_TOKEN)))
            .thenReturn(java.util.Optional.of(expired));

        assertThrows(DomainException.class, () -> sessionService.rotate(OLD_TOKEN));

        assertNotNull(expired.getRevokedAt());
        verify(sessionRepository).save(expired);
    }
}
