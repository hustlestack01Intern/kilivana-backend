package com.kilivana.auth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kilivana.common.exception.AuthenticationFailedException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Verification of Google's ID token, and — mostly — the requirement that every failure says the
 * same thing.
 *
 * <p>Tokens here are real. They are signed with a key pair generated in the test and the public
 * half is stubbed into the provider, so signature verification, expiry, issuer and audience are
 * exercised as production exercises them rather than mocked away. A test that asserts on a
 * verifier whose own dependency is a stub proves only that the stub was called.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoogleTokenVerifierTest {

    private static final String KID = "test-key-1";

    private static final String CLIENT_ID = "1234567890-abc.apps.googleusercontent.com";

    private static final String ISSUER = GoogleTokenVerifier.ISSUER;

    @Mock
    private GoogleJwksProvider jwksProvider;

    private MeterRegistry meterRegistry;

    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        when(jwksProvider.keyFor(any())).thenAnswer(invocation ->
                KID.equals(invocation.getArgument(0, String.class)) ? publicKey : null);
    }

    private GoogleTokenVerifier verifier() {
        return new GoogleTokenVerifier(jwksProvider, List.of(CLIENT_ID), meterRegistry);
    }

    @Test
    @DisplayName("a well formed token yields only what it asserted")
    void acceptsAValidToken() {
        GoogleIdentityClaims claims = verifier().verify(token(Map.of(
                "iss", ISSUER,
                "aud", CLIENT_ID,
                "sub", "google-subject-1",
                "email", "Person@Gmail.com",
                "email_verified", Boolean.TRUE,
                "name", "A Person")));

        assertThat(claims.subject()).isEqualTo("google-subject-1");
        assertThat(claims.normalizedEmail()).isEqualTo("person@gmail.com");
        assertThat(claims.emailVerified()).isTrue();
        assertThat(claims.name()).isEqualTo("A Person");
    }

    @Test
    @DisplayName("email_verified absent is false, never true")
    void absentEmailVerifiedIsFalse() {
        GoogleIdentityClaims claims = verifier().verify(token(Map.of(
                "iss", ISSUER,
                "aud", CLIENT_ID,
                "sub", "google-subject-1",
                "email", "someone@example.org",
                "hd", "example.org")));

        assertThat(claims.emailVerified()).isFalse();
        assertThat(claims.authoritative()).isFalse();
    }

    @Test
    @DisplayName("an HS256 token is refused before any key is looked up")
    void refusesAlgorithmConfusion() {
        // The classic forgery: sign with a symmetric secret and present it where an RSA public
        // key is expected. If the algorithm were not checked before key selection, jjwt would
        // be the only thing between that secret and an accepted token.
        Key symmetric = Keys.hmacShaKeyFor("a-string-at-least-256-bits-long-for-hs256!!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .setHeader(Map.of("alg", SignatureAlgorithm.HS256.getValue(), "kid", KID))
                .claims(Map.of(
                        "iss", ISSUER,
                        "aud", CLIENT_ID,
                        "sub", "attacker"))
                .signWith(symmetric)
                .compact();

        assertThatThrownBy(() -> verifier().verify(forged))
                .isInstanceOf(AuthenticationFailedException.class);
        verify(jwksProvider, never()).keyFor(any());
    }

    @Test
    @DisplayName("a token with no kid is refused")
    void refusesAMissingKid() {
        String noKid = Jwts.builder()
                .setHeader(Map.of("alg", SignatureAlgorithm.RS256.getValue()))
                .claims(Map.of("iss", ISSUER, "aud", CLIENT_ID, "sub", "attacker"))
                .signWith(keyPair.getPrivate(), SignatureAlgorithm.RS256)
                .compact();

        assertThatThrownBy(() -> verifier().verify(noKid))
                .isInstanceOf(AuthenticationFailedException.class);
        verify(jwksProvider, never()).keyFor(any());
    }

    @Test
    @DisplayName("every failure is one message, whatever the reason")
    void everyFailureIsIndistinguishable() {
        // The whole point of the class. A caller who can tell a bad audience from an expired
        // token from an unreachable Google learns about the deployment, and each answer is a
        // different thing to try next.
        String wrongAudience = token(Map.of("iss", ISSUER, "aud", "someone-elses-app", "sub", "s"));
        String wrongIssuer = token(Map.of("iss", "https://evil.example", "aud", CLIENT_ID, "sub", "s"));
        String expired = Jwts.builder()
                .setHeader(Map.of("alg", SignatureAlgorithm.RS256.getValue(), "kid", KID))
                .claims(Map.of("iss", ISSUER, "aud", CLIENT_ID, "sub", "s"))
                .issuedAt(new Date(System.currentTimeMillis() - 3_600_000))
                .expiration(new Date(System.currentTimeMillis() - 1_800_000))
                .signWith(keyPair.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
        String forged = tokenWithWrongKey();
        when(jwksProvider.keyFor("unknown-kid")).thenReturn(null);

        List<String> tokens = java.util.Arrays.asList(wrongAudience, wrongIssuer, expired, forged, "not-a-token", "", null);

        for (String candidate : tokens) {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> verifier().verify(candidate));
            assertThat(thrown)
                    .as("reason for %s", candidate == null ? "null" : "token " + candidate)
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessage("Google sign-in could not be verified");
        }
    }

    @Test
    @DisplayName("each failure reason is counted, and only from a bounded set")
    void reasonsAreBoundedAndCounted() {
        when(jwksProvider.keyFor("unknown-kid")).thenReturn(null);
        GoogleTokenVerifier verifier = verifier();

        assertThatThrownBy(() -> verifier.verify(token(Map.of(
                "iss", ISSUER, "aud", "not-this-client", "sub", "s")))).isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> verifier.verify(token(Map.of(
                "iss", "https://evil.example", "aud", CLIENT_ID, "sub", "s")))).isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> verifier.verify("no-kid-token")).isInstanceOf(AuthenticationFailedException.class);

        assertThat(failureCount("bad_aud")).isEqualTo(1.0);
        assertThat(failureCount("bad_iss")).isEqualTo(1.0);
        assertThat(failureCount("bad_sig")).isEqualTo(1.0);
        // Only the seven reasons this class declares may ever exist as a tag, or this endpoint
        // becomes a way to inflate a metric's cardinality with arbitrary text.
        assertThat(meterRegistry.get("kilivana.google.verify.failure").counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTag("reason"))
                        .isIn("unknown_kid", "bad_aud", "bad_iss", "expired", "bad_sig",
                                "jwks_unreachable", "jwks_timeout"));
    }

    @Test
    @DisplayName("a key server that timed out is not reported as an unknown key")
    void timeoutIsItsOwnReason() {
        // An operator debugging a Google outage watches this counter. If a timeout were counted
        // as unknown_kid, the outage and a routine rotation would be the same line and the
        // counter that should be moving would be sitting still.
        when(jwksProvider.keyFor(KID)).thenReturn(null);
        when(jwksProvider.lastOutcome()).thenReturn(GoogleJwksProvider.FetchOutcome.TIMEOUT);
        GoogleTokenVerifier verifier = verifier();

        assertThatThrownBy(() -> verifier.verify(token(Map.of(
                "iss", ISSUER, "aud", CLIENT_ID, "sub", "s"))))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(failureCount("jwks_timeout")).isEqualTo(1.0);
        assertThat(failureCount("unknown_kid")).isZero();
    }

    private double failureCount(String reason) {
        Counter counter = meterRegistry.find("kilivana.google.verify.failure").tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String token(Map<String, Object> claims) {
        return Jwts.builder()
                .setHeader(Map.of("alg", SignatureAlgorithm.RS256.getValue(), "kid", KID))
                .claims(claims)
                .signWith(keyPair.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
    }

    /** A correctly shaped token signed by a key the provider does not know. */
    private String tokenWithWrongKey() {
        try {
            KeyPair other = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            String value = Jwts.builder()
                    .setHeader(Map.of("alg", SignatureAlgorithm.RS256.getValue(), "kid", KID))
                    .claims(Map.of("iss", ISSUER, "aud", CLIENT_ID, "sub", "attacker"))
                    .signWith(other.getPrivate(), SignatureAlgorithm.RS256)
                    .compact();
            String[] parts = value.split("\\.");
            String genuineHeader = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"RS256\",\"kid\":\"test-key-1\"}".getBytes(StandardCharsets.UTF_8));
            return genuineHeader + "." + parts[1] + "." + parts[2];
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}