package com.kilivana.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kilivana.common.api.ApiError;
import com.kilivana.common.config.RequestContextFilter;
import com.kilivana.security.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits exactly four credential-abuse endpoints, keyed by rule name and client IP.
 * Every other path — including {@code POST /api/v1/contact-messages}, which is
 * {@code permitAll} and writes a row — is deliberately unthrottled: this filter runs before
 * {@code JwtAuthenticationFilter}, so a general rule could only ever key on the egress
 * address, which collapses a carrier-grade NAT cohort onto one bucket and lets a Redis
 * blip answer 503 site-wide. There is no general rate limit, and the configuration
 * surface that implied one has been deleted. The four limits are read here and nowhere else,
 * so there is no second table to drift out of step with this one. The per-account login limit
 * is deliberately NOT here: it lives in {@code AuthService}, keyed on the account rather than
 * on an address this filter had to derive from a header.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Never rate limited. Railway's healthcheck is a deploy-time gate, probed from the
     * single hostname {@code healthcheck.railway.app}; behind a proxy that address is also
     * what the app sees for every one of those probes. Letting a probe take a bucket
     * means a customer's request rate is spent by the platform's own traffic, and a
     * throttled probe is a failed deploy. The exemption is evaluated before any bucket
     * lookup so an internal path cannot consume quota even transiently.
     */
    private static final String INTERNAL_PATH_PREFIX = "/actuator/";

    /**
     * How long a refused peer stays named, and how many are held in memory doing it. Same budget
     * and same reasoning as the resolver's: a distinct-peer cap of eight was reached by a
     * multi-address platform edge within the first minute and silenced this line for the life of
     * the process, which is the outage going silent rather than being reported. See
     * {@link DiagnosticBudget}.
     */
    private static final long DIAGNOSTIC_INTERVAL_MILLIS = 60_000L;

    private static final int MAX_TRACKED_UNRESOLVED_PEERS = 256;

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;

    /**
     * Peers already named in the unresolved-identity log, per rule, on a time budget. Concurrent
     * because this runs on every credential request from many threads, and per-instance so it
     * cannot leak between test contexts that each build their own filter.
     */
    private final DiagnosticBudget unresolvedIdentityBudget;

    public RateLimitFilter(
            RateLimitProperties properties,
            RateLimiter rateLimiter,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper) {
        this(properties, rateLimiter, clientIpResolver, objectMapper, Clock.systemUTC());
    }

    /** The clock is injected so a test can exercise a whole suppression interval without sleeping. */
    RateLimitFilter(
            RateLimitProperties properties,
            RateLimiter rateLimiter,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.unresolvedIdentityBudget =
                new DiagnosticBudget(clock, DIAGNOSTIC_INTERVAL_MILLIS, MAX_TRACKED_UNRESOLVED_PEERS);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (properties != null && !properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isInternalPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimit rule = ruleFor(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        ClientIpResolver.Resolution resolution = clientIpResolver.resolveAttribution(request);
        if (!resolution.attributable()) {
            // Refused rather than rate-limited. Bucketing this request would put it in the
            // shared bucket that every other caller behind this peer is already in, which is the
            // outage this branch exists to stop: six forged requests would otherwise spend the
            // credential budget of the whole platform. The limiter is not consulted, so nothing
            // is consumed and a later honest request through the same peer is unaffected.
            reportUnresolvedIdentity(rule, resolution);
            writeError(request, response, 503, "CLIENT_IDENTITY_UNRESOLVED",
                    "The request could not be attributed to a client address and was not "
                            + "processed. Please retry shortly.");
            return;
        }
        String clientKey = rule.name() + ":" + resolution.clientIp();
        Duration window = WINDOW;
        RateLimitResult result = rateLimiter.tryConsume(clientKey, rule.limit(), window);
        if (result.status() == RateLimitResult.Status.ALLOWED) {
            filterChain.doFilter(request, response);
            return;
        }
        if (result.status() == RateLimitResult.Status.UNAVAILABLE) {
            writeError(request, response, 503, "RATE_LIMIT_UNAVAILABLE", "Request protection is temporarily unavailable");
            return;
        }
        response.setHeader("Retry-After", Long.toString(result.retryAfterSeconds()));
        writeError(request, response, 429, "RATE_LIMITED", "Too many requests. Please retry later.");
    }

    /**
     * Names a peer whose credential requests cannot be attributed, once per peer per rule per
     * minute. The reason is a fixed machine token from {@link ClientIpResolver} and the peer is a
     * socket address; the client's forwarded address is absent because it is attacker-controlled
     * text, and writing it into the deploy log would hand anyone who can send a request the
     * ability to.
     *
     * <p>Two messages, because there are two causes with two opposite remedies and this used to
     * name only one of them. An untrusted peer — {@code reason=peer-not-trusted} — is missing from
     * {@code TRUSTED_PROXIES}; telling such an operator to check {@code TRUSTED_PROXY_HOPS} sends
     * them to a variable that cannot be the cause, since the hop count is only consulted for a peer
     * that is <em>already</em> trusted. That was the only line emitted when an unconfigured edge
     * forwarded no header at all, so it was the line an operator with this exact outage was reading.
     */
    private void reportUnresolvedIdentity(RateLimit rule, ClientIpResolver.Resolution resolution) {
        String reportKey = rule.name() + ":" + resolution.clientIp();
        if (!unresolvedIdentityBudget.shouldReport(reportKey)) {
            return;
        }
        long suppressed = unresolvedIdentityBudget.takeSuppressedIfDue();
        if (suppressed > 0) {
            log.error("{} is STILL being refused: {} request(s) in the last minute were collapsed "
                            + "into this line, across {} peer(s) tracked for this rule. The remedy "
                            + "depends on the reason named below and is the same every time: "
                            + "TRUSTED_PROXIES for peer-not-trusted, TRUSTED_PROXY_HOPS otherwise. "
                            + "No bucket was consumed by any of them.",
                    rule.name(), suppressed, unresolvedIdentityBudget.trackedKeyCount());
        }
        if (ClientIpResolver.REASON_PEER_NOT_TRUSTED.equals(resolution.reason())) {
            logUntrustedPeer(rule, resolution);
        } else {
            logUnverifiableChain(rule, resolution);
        }
    }

    private void logUntrustedPeer(RateLimit rule, ClientIpResolver.Resolution resolution) {
        log.error("Refusing {} for socket peer {} with code CLIENT_IDENTITY_UNRESOLVED "
                        + "(reason={}): proxy trust is ON and this peer is not in "
                        + "kilivana.security.proxy.trusted-proxies, so it is not the client — it is "
                        + "either the edge that is missing from TRUSTED_PROXIES or a caller that "
                        + "reached this service directly and bypassed the edge. Either way every "
                        + "caller behind it shares one address, so the four credential limits would "
                        + "become one platform-wide budget and one anonymous actor could spend "
                        + "everybody's. The request is REFUSED with 503 CLIENT_IDENTITY_UNRESOLVED "
                        + "and no bucket is consumed. THE FIX IS TRUSTED_PROXIES: add this peer's "
                        + "address or the narrowest CIDR containing it. Do NOT look at "
                        + "TRUSTED_PROXY_HOPS — the hop count is never consulted for a peer that is "
                        + "not trusted, so it cannot be the cause of this. Do NOT turn "
                        + "TRUST_FORWARDED_HEADERS off either: that reinstates the shared bucket and "
                        + "stops this line from being written at all. The client's forwarded address "
                        + "is not logged.",
                rule.name(), resolution.clientIp(), resolution.reason());
    }

    private void logUnverifiableChain(RateLimit rule, ClientIpResolver.Resolution resolution) {
        log.error("Refusing {} for socket peer {} with code CLIENT_IDENTITY_UNRESOLVED "
                        + "(reason={}): this deployment trusts forwarded headers from this peer, but the "
                        + "X-Forwarded-For it sent carried no client address this service could "
                        + "verify, so the request cannot be keyed on a bucket of its own. Either a "
                        + "client is writing the header itself or an unconfigured hop is forging "
                        + "one. No bucket was consumed and no client address was logged. The fix here "
                        + "is TRUSTED_PROXY_HOPS: confirm the edge overwrites X-Forwarded-For and that "
                        + "TRUSTED_PROXY_HOPS equals the number of proxies that append to it. If this "
                        + "peer is absent from TRUSTED_PROXIES instead, the peer-not-trusted line "
                        + "above names the real fix.",
                rule.name(), resolution.clientIp(), resolution.reason());
    }

    private boolean isInternalPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith(INTERNAL_PATH_PREFIX) || path.equals("/actuator");
    }

    private RateLimit ruleFor(HttpServletRequest request) {
        String path = stripPathParamsAndContext(request);
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/signin")) {
            return new RateLimit("login", properties.loginRequestsPerMinute());
        }
        if (path.equals("/api/v1/auth/signup") || path.equals("/api/v1/auth/register")) {
            return new RateLimit("register", properties.registrationRequestsPerMinute());
        }
        if (path.equals("/api/v1/auth/forgot-password")) {
            return new RateLimit("forgot-password", properties.forgotPasswordRequestsPerMinute());
        }
        if (path.equals("/api/v1/admins/signup")) {
            return new RateLimit("admin-bootstrap", properties.adminBootstrapRequestsPerMinute());
        }
        // The Google surface is rate limited here, by exact path, rather than by a wildcard
        // over "/api/v1/auth/**". A wildcard would silently start covering any auth endpoint
        // added later with no deliberate decision, which is the opposite of how the rest of
        // this filter is allowed to grow.
        //
        // `/link` is the interesting one: it is authenticated, but it takes a password, so it
        // is held to the login budget. Being behind a valid session is not a licence to guess.
        if (path.equals("/api/v1/auth/google")) {
            return new RateLimit("google-handshake", properties.googleHandshakeRequestsPerMinute());
        }
        if (path.equals("/api/v1/auth/google/verify-email")) {
            return new RateLimit("google-verify-email", properties.googleVerifyEmailRequestsPerMinute());
        }
        if (path.equals("/api/v1/auth/google/onboard")) {
            return new RateLimit("google-onboard", properties.googleOnboardRequestsPerMinute());
        }
        if (path.equals("/api/v1/auth/google/link")) {
            return new RateLimit("google-link", properties.googleLinkRequestsPerMinute());
        }
        return null;
    }

    private String stripPathParamsAndContext(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        int semicolon = path.indexOf(';');
        if (semicolon >= 0) {
            path = path.substring(0, semicolon);
        }
        return path;
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String requestId = MDC.get(RequestContextFilter.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = (String) request.getAttribute(RequestContextFilter.REQUEST_ID);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader(RequestContextFilter.REQUEST_ID_HEADER);
        }
        if (requestId != null && !requestId.isBlank()) {
            response.setHeader(RequestContextFilter.REQUEST_ID_HEADER, requestId);
        }
        ApiError error = new ApiError(
                code,
                message,
                status,
                request.getRequestURI(),
                requestId,
                OffsetDateTime.now(),
                List.of());
        objectMapper.writeValue(response.getOutputStream(), error);
    }

    private record RateLimit(String name, int limit) {
    }
}
