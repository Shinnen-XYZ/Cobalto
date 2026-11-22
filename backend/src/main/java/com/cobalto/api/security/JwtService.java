package com.cobalto.api.security;

import com.cobalto.api.domain.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!migrate")
public class JwtService {
    private final SecretKey signingKey;
    private final long tokenLifetimeSeconds;

    public JwtService(
        @Value("${cobalto.security.jwt-secret}") String encodedSecret,
        @Value("${cobalto.security.access-token-minutes:15}") long accessTokenMinutes
    ) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(encodedSecret));
        this.tokenLifetimeSeconds = Math.multiplyExact(accessTokenMinutes, 60);
    }

    public String issueToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.getId().toString())
            .claim("role", "USER")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(tokenLifetimeSeconds)))
            .signWith(signingKey)
            .compact();
    }

    public Optional<UUID> readUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
            if (!"USER".equals(claims.get("role", String.class))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public long getTokenLifetimeSeconds() {
        return tokenLifetimeSeconds;
    }
}
