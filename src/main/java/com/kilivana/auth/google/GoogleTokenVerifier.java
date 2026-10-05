package com.kilivana.auth.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kilivana.common.exception.AuthenticationFailedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Verifies a Google ID token and returns only what it asserted.
 *
 * <p>Every failure — wrong audience, wrong issuer, expired, forged signature, unknown key,
 * unreachable key server — raises the same {@link AuthenticationFailedException} with the same
 * message. The reason is counted on {@code kilivana.google.verify.failure} and appears nowhere
 * else: a caller who can tell "your token was minted for a different app" from "we could not
 * reach Google" learns something about the deployment, and each of those reasons is a
 * different thing to try next.
 *
 * <p>The token is never logged, not even truncated and not even at debug. It is a bearer
 * credential for the Google account it names.
 */
@Component
@ConditionalOnProperty(prefix = "kilivana.security.google", name = "enabled", havingValue = "true")
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);

    /**
     * The issuer is a constant because it is part of what makes the signature meaningful.
     * A configurable issuer would let any two-party token be accepted as a Google token.
     */
    static final String ISSUER = "https://accounts.google.com";

    /**
     * Google's clock, our tolerance. Absorbs ordinary skew between a handset and Google's
     * servers without being wide enough to make a meaningfully expired token acceptable.
     */
    private static final long CLOCK_SKEW_SECONDS = 300L;

    private static final String EXPECTED_ALGORITHM = "RS256";

    private final GoogleJwksProvider jwksProvider;
    private final List<String> clientIds;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Reason, Counter> failureCounters = new EnumMap<>(Reason.class);

    public GoogleTokenVerifier(GoogleJwksProvider jwksProvider, GoogleProperties properties, MeterRegistry registry) {
        this(jwksProvider, properties.clientIds(), registry);
    }

    GoogleTokenVerifier(GoogleJwksProvider jwksProvider, List<String> clientIds, MeterRegistry registry) {
        this.jwksProvider = jwksProvider;
        this.clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
        for (Reason reason : Reason.values()) {
            failureCounters.put(reason, registry.counter(
                    "kilivana.google.verify.failure", "reason", reason.token));
        }
    }

    public GoogleIdentityClaims verify(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw fail(Reason.BAD_SIG);
        }

        // The header is read BEFORE any key is selected. An HS256 token presented against an
        // RSA public key is the classic algorithm-confusion forgery, and whether it is refused
        // depends entirely on this check: without it, jjwt is the only thing standing between
        // an attacker's symmetric secret and an RSA public key used as one.
        JsonNode header = readHeader(idToken);
        if (header == null || !EXPECTED_ALGORITHM.equals(header.path("alg").asText(null))) {
            throw fail(Reason.BAD_SIG);
        }
        String kid = header.path("kid").asText(null);
        if (kid == null || kid.isBlank()) {
            throw fail(Reason.UNKNOWN_KID);
        }

        PublicKey key = jwksProvider.keyFor(kid);
        if (key == null) {
            // Distinguish "we could not ask" from "Google has no such key". Collapsing them
            // makes a Google outage and a routine key rotation the same event, and the counter
            // an operator watches during an outage is the one that stops moving.
            throw fail(keyLookupFailure());
        }

        Claims claims;
        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(key)
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(idToken);
            claims = parsed.getPayload();
        } catch (ExpiredJwtException exception) {
            throw fail(Reason.EXPIRED);
        } catch (JwtException | IllegalArgumentException exception) {
            throw fail(Reason.BAD_SIG);
        }

        // Issuer and audience are checked here rather than with requireIssuer/requireAudience
        // so that each has its own reason. Deciding between "bad_aud" and "bad_iss" by
        // matching exception message text would be a silent dependency on a library's wording.
        if (!ISSUER.equals(claims.getIssuer())) {
            throw fail(Reason.BAD_ISS);
        }
        if (!audienceMatches(claims)) {
            throw fail(Reason.BAD_AUD);
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw fail(Reason.BAD_SIG);
        }

        return new GoogleIdentityClaims(
                subject,
                claims.get("email", String.class),
                emailVerified(claims),
                claims.get("hd", String.class),
                claims.get("name", String.class));
    }

    /**
     * {@code email_verified} is absent-means-false, never absent-means-true.
     *
     * <p>Anything that is not the boolean {@code true} — missing, false, or the string "true"
     * from a token this service did not learn to trust — counts as unverified. The permissive
     * reading of an absent claim is the entire attack this design exists to prevent.
     */
    private boolean emailVerified(Claims claims) {
        return Boolean.TRUE.equals(claims.get("email_verified"));
    }

    private boolean audienceMatches(Claims claims) {
        Set<String> audience = claims.getAudience();
        if (audience == null || audience.isEmpty()) {
            return false;
        }
        for (String clientId : clientIds) {
            if (audience.contains(clientId)) {
                return true;
            }
        }
        return false;
    }

    private Reason keyLookupFailure() {
        return switch (jwksProvider.lastOutcome()) {
            case TIMEOUT -> Reason.JWKS_TIMEOUT;
            case UNREACHABLE, MALFORMED -> Reason.JWKS_UNREACHABLE;
            case NONE -> Reason.UNKNOWN_KID;
        };
    }

    private JsonNode readHeader(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private AuthenticationFailedException fail(Reason reason) {
        failureCounters.get(reason).increment();
        log.warn("Google ID token verification failed, reason={}", reason.token);
        // One message, every reason. Nothing about the token, and nothing about which check
        // rejected it.
        return new AuthenticationFailedException("Google sign-in could not be verified");
    }

    /**
     * The bounded set of reasons a verification can fail. Bounded so this endpoint cannot be
     * used to inflate a metric's cardinality with arbitrary text.
     */
    enum Reason {

        UNKNOWN_KID("unknown_kid"),
        BAD_AUD("bad_aud"),
        BAD_ISS("bad_iss"),
        EXPIRED("expired"),
        BAD_SIG("bad_sig"),
        JWKS_UNREACHABLE("jwks_unreachable"),
        JWKS_TIMEOUT("jwks_timeout");

        private final String token;

        Reason(String token) {
            this.token = token;
        }
    }
}