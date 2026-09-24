package com.kilivana.security;

import com.kilivana.users.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final int MINIMUM_SECRET_BYTES = 32;
    private static final int MINIMUM_DISTINCT_SECRET_CHARACTERS = 12;

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        validateProperties(properties);
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusMinutes(properties.accessTokenMinutes());
        return Jwts.builder()
                .issuer(properties.issuer())
                .claim("aud", properties.audience())
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now.toInstant()))
                .expiration(Date.from(expiresAt.toInstant()))
                .signWith(signingKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .requireAudience(properties.audience())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parse(token).getSubject());
    }

    private void validateProperties(JwtProperties properties) {
        if (properties == null) {
            throw new IllegalStateException("JWT configuration is required");
        }
        if (isBlank(properties.issuer()) || isBlank(properties.audience())) {
            throw new IllegalStateException("JWT issuer and audience must be configured");
        }
        if (properties.accessTokenMinutes() <= 0 || properties.accessTokenMinutes() > 30) {
            throw new IllegalStateException("JWT access token lifetime must be between 1 and 30 minutes");
        }
        if (properties.refreshTokenDays() <= 0 || properties.refreshTokenDays() > 90) {
            throw new IllegalStateException("JWT refresh token lifetime must be between 1 and 90 days");
        }
        if (isBlank(properties.secret())) {
            throw new IllegalStateException(
                    "kilivana.security.jwt.secret must be configured (set JWT_SECRET); no default fallback is allowed");
        }
        byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        long distinctCharacters = properties.secret().chars()
                .distinct()
                .count();
        if (secretBytes.length < MINIMUM_SECRET_BYTES || distinctCharacters < MINIMUM_DISTINCT_SECRET_CHARACTERS) {
            throw new IllegalStateException("kilivana.security.jwt.secret must be high entropy and at least 32 bytes");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
