package com.kilivana.auth.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Fetches and caches Google's JSON Web Key Set, and hands out the RSA public keys that verify
 * its ID tokens.
 *
 * <p><strong>The JWKS URI is a constant, not a property.</strong> A configurable URI on an
 * unauthenticated fetch path, whose response body becomes key material, is a server-side
 * request forgery primitive with a very short fuse: point it at a host the process can reach,
 * return a document whose modulus you control, and every ID token this service accepts is one
 * you minted. There is no deployment where pointing this anywhere but Google is correct, so
 * there is nothing for an operator to configure.
 *
 * <p><strong>Both timeouts are set explicitly.</strong> {@code RestClient.create()} builds a
 * JDK {@code HttpClient} with no read timeout at all, so a stalling JWKS endpoint would park
 * the calling Tomcat worker indefinitely — and login, orders and payments share that worker
 * pool. The precedent for the magnitudes is {@code application.yml}'s one-second Redis budget.
 */
@Component
@ConditionalOnProperty(prefix = "kilivana.security.google", name = "enabled", havingValue = "true")
public class GoogleJwksProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleJwksProvider.class);

    /** Never configurable. See the class comment. */
    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    /** The only algorithm whose keys are ever loaded. */
    private static final String EXPECTED_ALGORITHM = "RS256";

    /**
     * Ceiling on forced refetches per minute, across the whole process.
     *
     * <p>{@code kid} is attacker-controlled text. Without this bound, tokens carrying a fresh
     * random {@code kid} would make this service fetch the key set once per request, turning a
     * Google outage into an outbound request amplifier pointed at a third party. Two a minute
     * is generous for a real rotation and keeps the worst case small.
     */
    private static final int MAX_FORCED_REFRESHES_PER_MINUTE = 2;

    private static final long REFRESH_WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String jwksUri;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Counter refreshCounter;

    /**
     * Single-flight. {@code kid} misses arrive in a burst when Google rotates keys, and N
     * concurrent requests must cause one fetch. Reentrant rather than {@code synchronized} so
     * the permit bookkeeping stays outside the critical section.
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile boolean everLoaded;
    private volatile FetchOutcome lastOutcome = FetchOutcome.NONE;

    private final AtomicInteger forcedRefreshesThisWindow = new AtomicInteger();
    private final AtomicLongHolder refreshWindowStartedAt = new AtomicLongHolder();

    public GoogleJwksProvider(MeterRegistry meterRegistry) {
        this(
                defaultRestClient(),
                new ObjectMapper(),
                JWKS_URI,
                meterRegistry,
                Clock.systemUTC(),
                System::nanoTime);
    }

    /** Package-private so a test can serve a key set it generated, without a network. */
    GoogleJwksProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            String jwksUri,
            MeterRegistry meterRegistry,
            Clock clock,
            LongSupplier nanoTime) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.jwksUri = jwksUri;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.refreshCounter = meterRegistry.counter("kilivana.google.jwks.refresh", "uri", jwksUri);
    }

    /**
     * Both timeouts set explicitly.
     *
     * <p>{@link SimpleClientHttpRequestFactory} rather than {@code RestClient.create()}, which
     * builds a JDK {@code HttpClient} with no read timeout at all — see the class comment for
     * why a stalled fetch cannot be allowed to hold a worker.
     */
    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * The key for a {@code kid}, refetching at most once per burst.
     *
     * <p>Returns {@code null} for an unknown {@code kid}. The caller counts that as a
     * verification failure and returns one fixed error; this class never sees a token.
     */
    public PublicKey keyFor(String kid) {
        if (kid == null || kid.isBlank()) {
            return null;
        }
        PublicKey cached = keys.get(kid);
        if (cached != null) {
            return cached;
        }
        refreshIfPermitted(kid);
        return keys.get(kid);
    }

    private void refreshIfPermitted(String kid) {
        if (!consumeRefreshPermit()) {
            // Deliberately silent: the caller is already counting an unknown_kid, and a line
            // here would let a caller who rotates kid values write to the log at will.
            return;
        }
        refreshLock.lock();
        try {
            // Another thread may have refreshed while this one waited for the lock. Re-check
            // before spending a network call, or single-flight degrades into "one fetch per
            // thread unlucky enough to miss".
            if (keys.containsKey(kid)) {
                return;
            }
            fetch();
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * A forced refetch is rate limited. The very first load of the process is NOT counted —
     * it is a cold start, not an attacker — so {@link #everLoaded} is what distinguishes them.
     */
    private boolean consumeRefreshPermit() {
        if (!everLoaded) {
            return true;
        }
        long now = nanoTime.getAsLong();
        if (refreshWindowStartedAt.advanceIfElapsed(now, REFRESH_WINDOW_NANOS)) {
            forcedRefreshesThisWindow.set(0);
        }
        return forcedRefreshesThisWindow.incrementAndGet() <= MAX_FORCED_REFRESHES_PER_MINUTE;
    }

    private void fetch() {
        try {
            byte[] body = restClient.get()
                    .uri(jwksUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(byte[].class);
            if (body == null) {
                throw new IllegalStateException("the key set response had no body");
            }
            Map<String, PublicKey> refreshed = parse(body);
            this.keys = refreshed;
            this.everLoaded = true;
            this.lastOutcome = FetchOutcome.NONE;
            refreshCounter.increment();
            log.info("Refreshed the Google signing key set: {} key(s)", refreshed.size());
        } catch (ResourceAccessException exception) {
            refreshCounter.increment();
            if (exception.getCause() instanceof SocketTimeoutException) {
                this.lastOutcome = FetchOutcome.TIMEOUT;
                log.error("Fetching the Google signing key set timed out after {}: Google sign-in is "
                        + "unavailable until it recovers", READ_TIMEOUT);
            } else {
                this.lastOutcome = FetchOutcome.UNREACHABLE;
                log.error("Fetching the Google signing key set failed, exceptionType={}: Google sign-in is "
                        + "unavailable until it recovers", exception.getClass().getName());
            }
        } catch (RuntimeException | IOException exception) {
            refreshCounter.increment();
            this.lastOutcome = FetchOutcome.MALFORMED;
            log.error("The Google signing key set could not be read, exceptionType={}: Google sign-in is "
                    + "unavailable until it recovers", exception.getClass().getName());
        }
    }

    private Map<String, PublicKey> parse(byte[] body) throws IOException {
        JsonNode keyArray = objectMapper.readTree(body).path("keys");
        if (!keyArray.isArray()) {
            throw new IllegalArgumentException("the key set carried no keys array");
        }
        Map<String, PublicKey> parsed = new HashMap<>();
        for (JsonNode node : keyArray) {
            String kid = node.path("kid").asText(null);
            String kty = node.path("kty").asText(null);
            String alg = node.path("alg").asText(null);
            if (kid == null || kid.isBlank() || !"RSA".equals(kty) || !EXPECTED_ALGORITHM.equals(alg)) {
                // Nothing that is not RSA/RS256 is loaded at all, so the map contains no key a
                // differently-algorithmed token could ever select.
                continue;
            }
            PublicKey key = rsaPublicKey(node.path("n").asText(null), node.path("e").asText(null));
            if (key != null) {
                parsed.put(kid, key);
            }
        }
        return Map.copyOf(parsed);
    }

    private PublicKey rsaPublicKey(String modulus, String exponent) {
        if (modulus == null || exponent == null) {
            return null;
        }
        try {
            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(modulus));
            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(exponent));
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            log.warn("A Google signing key entry could not be decoded and was skipped, exceptionType={}",
                    exception.getClass().getName());
            return null;
        }
    }

    /** Whether a key set has been fetched at least once in this process. */
    boolean hasLoadedKeys() {
        return everLoaded;
    }

    /**
     * How the most recent fetch ended, so the verifier can tell "Google has no key with this
     * kid" from "we never got to ask". Without that distinction a Google outage and a
     * perfectly ordinary rotated key are the same event, and the one number an operator needs
     * during an outage is the one that stops moving.
     */
    FetchOutcome lastOutcome() {
        return lastOutcome;
    }

    enum FetchOutcome {

        /** No fetch has happened, or the last one succeeded. */
        NONE,
        TIMEOUT,
        UNREACHABLE,
        MALFORMED
    }

    /** Compare-and-set on a nanos timestamp, so the window reset needs no lock of its own. */
    private static final class AtomicLongHolder {

        private final java.util.concurrent.atomic.AtomicLong value = new java.util.concurrent.atomic.AtomicLong();

        boolean advanceIfElapsed(long now, long windowNanos) {
            long previous = value.get();
            if (previous != 0L && now - previous < windowNanos) {
                return false;
            }
            return value.compareAndSet(previous, now);
        }
    }
}