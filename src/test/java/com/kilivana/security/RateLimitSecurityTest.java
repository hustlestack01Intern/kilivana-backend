package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kilivana.security.config.ProxyProperties;
import com.kilivana.security.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class RateLimitSecurityTest {

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private FilterChain filterChain;

    @Test
    void appliesEndpointSpecificLimitAndReturnsRetryAfter() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, "memory", 1, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100);
        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        when(rateLimiter.tryConsume(anyString(), eq(1), any())).thenReturn(RateLimitResult.limited(9));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Request-Id", "rate-request");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("9");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("rate-request");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("an internal probe path never consumes a rate-limit bucket, at any limit")
    void actuatorPathsAreNeverRateLimited() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, "memory", 1, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100);
        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        // Whatever the limiter would answer, the filter must not even ask: Railway's
        // deploy-time probe arrives from one shared proxy address, and a probe that takes
        // a bucket spends a customer's allowance. Marked lenient so the assertion below,
        // not Mockito's unused-stub check, is what fails if the exemption is removed.
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(RateLimitResult.limited(30));

        for (String path : List.of(
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/health",
                "/actuator/prometheus")) {
            for (int attempt = 0; attempt < 5; attempt++) {
                // POST as well as GET: the limiter only ever considers writes, so a GET-only
                // assertion would hold even with the exemption deleted.
                for (String method : List.of("GET", "POST")) {
                    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
                    request.setRemoteAddr("192.0.2.10");
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    filter.doFilter(request, response, filterChain);

                    assertThat(response.getStatus()).as("%s %s attempt %s", method, path, attempt).isEqualTo(200);
                }
            }
        }

        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
        verify(filterChain, times(40)).doFilter(any(), any());
    }

    @Test
    @DisplayName("the ordinary endpoint at the same limit is still throttled")
    void ordinaryEndpointIsStillRateLimited() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, "memory", 1, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100);
        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        when(rateLimiter.tryConsume(anyString(), eq(1), any())).thenReturn(RateLimitResult.limited(9));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("a disabled limiter is skipped entirely, internal paths included")
    void disabledLimiterShortCircuits() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(false, "memory", 1, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100);
        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);

        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("the standing falsifier: no path without a specialised rule consumes a bucket")
    void noPathWithoutASpecialisedRuleConsumesABucket() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100);
        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        // The invariant this pins: there is no general rate limit, and the four credential
        // rules are the whole surface. The day someone re-wires a catch-all "all" rule,
        // this goes red — which is the point, because a catch-all keyed on the egress IP
        // collapses a carrier-grade NAT cohort onto one bucket and turns a Redis blip into
        // a 503 for the whole site. Unthrottled is the deliberate answer, not an oversight.
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(RateLimitResult.allowed());

        for (String path : List.of(
                "/api/v1/products",
                "/api/v1/orders",
                "/api/v1/contact-messages",
                "/api/v1/cart/checkout",
                "/",
                "/swagger-ui/index.html")) {
            for (String method : List.of("GET", "POST", "PATCH", "DELETE")) {
                MockHttpServletRequest request = new MockHttpServletRequest(method, path);
                request.setRemoteAddr("192.0.2.10");
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(request, response, filterChain);

                assertThat(response.getStatus()).as("%s %s", method, path).isEqualTo(200);
            }
        }

        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("all four credential-abuse rules still consume a bucket after the legacy constructor is gone")
    void everyCredentialRuleStillLimits() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100);
        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(RateLimitResult.limited(7));

        // Deleting the general limit must not have deleted the four limits that do run:
        // the rule name, the per-rule limit and the shared one-minute window are all pinned
        // here, so a rule silently falling out of ruleFor is a failing test, not a surprise.
        for (String[] rule : List.of(
                new String[] {"/api/v1/auth/login", "login:192.0.2.10", "5", "1"},
                new String[] {"/api/v1/auth/signup", "register:192.0.2.10", "5", "1"},
                // signup and register are aliases and deliberately share one bucket.
                new String[] {"/api/v1/auth/register", "register:192.0.2.10", "5", "2"},
                new String[] {"/api/v1/auth/forgot-password", "forgot-password:192.0.2.10", "3", "1"},
                new String[] {"/api/v1/admins/signup", "admin-bootstrap:192.0.2.10", "3", "1"})) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", rule[0]);
            request.setRemoteAddr("192.0.2.10");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).as(rule[0]).isEqualTo(429);
            verify(rateLimiter, times(Integer.parseInt(rule[3])))
                    .tryConsume(rule[1], Integer.parseInt(rule[2]), Duration.ofMinutes(1));
        }
    }

    @Test
    @DisplayName("a trusted peer sending a wrong-length header is refused, and the limiter is never asked")
    void anUnattributableRequestIsRefusedWithoutTouchingTheLimiter() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100);
        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(true, List.of("10.0.0.0/8"), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        // Lenient so the assertion below, not Mockito's unused-stub check, is what fails if the
        // filter starts consulting the limiter on this branch: the point of the 503 is that the
        // request spends nothing.
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(RateLimitResult.allowed());

        // Two entries for a one-proxy deployment. Under the old behaviour this keyed the request
        // on the shared peer bucket, so six of these spent login:5/min for every other user of the
        // platform. It must now be refused, and refused before any bucket is touched.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("CLIENT_IDENTITY_UNRESOLVED");
        assertThat(response.getContentAsString())
                .as("the client's forwarded address is attacker-controlled text and must not be echoed")
                .doesNotContain("1.1.1.1")
                .doesNotContain("2.2.2.2");
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("a trusted peer sending no header at all is refused the same way")
    void anAbsentHeaderFromATrustedPeerIsRefused() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100),
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(true, List.of("10.0.0.0/8"), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.1.2.3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("CLIENT_IDENTITY_UNRESOLVED");
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an unattributable request on an unlimited path is never even examined")
    void theUnattributableRefusalOnlyTouchesTheFourCredentialRules() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100),
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(true, List.of("10.0.0.0/8"), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(RateLimitResult.limited(30));

        // A trusted peer whose header cannot be verified is refused on login — and must be
        // completely unaffected on every other route, including a write that persists a row. The
        // refusal is scoped to the four rules for exactly this reason.
        for (String path : List.of("/api/v1/contact-messages", "/api/v1/products", "/api/v1/cart/checkout")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr("10.1.2.3");
            request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).as("%s", path).isEqualTo(200);
        }

        verify(filterChain, times(3)).doFilter(any(), any());
    }

    @Test
    @DisplayName("with proxy trust DISABLED every peer is attributable and nothing is refused")
    void trustDisabledLeavesEveryPeerAttributable() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100),
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(RateLimitResult.allowed());

        // trust-forwarded-headers=false is the operator saying there is no proxy in front of
        // this service. The socket peer is then genuinely the client, so it is attributable by
        // construction and the 503 branch is unreachable in this configuration.
        //
        // Note this is NOT a remedy for the collapse. It is the configuration UNDER WHICH the
        // collapse happens: one bucket per rule for everything behind a shared edge. It is kept
        // here because it is a legitimate posture (no proxy at all), not because it is safe
        // behind a PaaS edge — see the next test, which is the posture this repository deploys to.
        for (String forwardedFor : List.of("1.1.1.1, 2.2.2.2", "not-an-address", "198.51.100.7")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("192.0.2.10");
            request.addHeader("X-Forwarded-For", forwardedFor);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).as("X-Forwarded-For: %s", forwardedFor).isEqualTo(200);
        }

        verify(rateLimiter, times(3)).tryConsume("login:192.0.2.10", 5, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("with proxy trust ON, a peer outside the trusted list is refused, not bucketed")
    void anUntrustedPeerIsRefusedRatherThanBucketedOnItsOwnAddress() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100),
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(true, List.of("10.0.0.0/8"), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        // Lenient so the assertion below, not Mockito's unused-stub check, is what fails if the
        // filter starts consulting the limiter on this branch: the point of the 503 is that the
        // request spends nothing.
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(RateLimitResult.allowed());

        // This is the Render/Railway posture in miniature. Proxy trust is on, so the operator has
        // declared a proxy in front of this service — but the socket peer is a PUBLIC address in
        // none of the shipped ranges (loopback + RFC1918 + ULA), because the platform edge
        // terminates at public addresses. Under the previous behaviour every one of these
        // resolved to "192.0.2.10" and was bucketed there, so five requests from an anonymous
        // caller spent login:5/min for the entire platform.
        for (String forwardedFor : List.of("198.51.100.7", "1.1.1.1, 2.2.2.2", "not-an-address")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("192.0.2.10");
            request.addHeader("X-Forwarded-For", forwardedFor);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus())
                    .as("X-Forwarded-For: %s — bucketing here would merge every caller behind "
                            + "this peer into one bucket", forwardedFor)
                    .isEqualTo(503);
            assertThat(response.getContentAsString()).contains("CLIENT_IDENTITY_UNRESOLVED");
        }

        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("an untrusted peer's refusal never spends the shared budget an honest caller needs")
    void anUntrustedPeerCannotSpendTheSharedBudget() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100),
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(true, List.of("10.0.0.0/8"), 1)),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(RateLimitResult.allowed());

        // Twenty attempts from an anonymous caller sharing one socket peer with everybody else.
        for (int attempt = 0; attempt < 20; attempt++) {
            MockHttpServletRequest attacker = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            attacker.setRemoteAddr("192.0.2.10");
            attacker.addHeader("X-Forwarded-For", "198.51.100." + attempt);
            MockHttpServletResponse attackerResponse = new MockHttpServletResponse();

            filter.doFilter(attacker, attackerResponse, filterChain);

            assertThat(attackerResponse.getStatus()).isEqualTo(503);
        }

        // The half that matters. A bucket keyed on the shared peer would be long spent by now, so
        // the honest caller behind the same peer gets 429 — the whole platform is locked out by
        // one anonymous actor. Under the refusal, nothing was consumed and the limiter was never
        // even reached.
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("the forwarded address is used only behind a configured trusted proxy")
    void forwardedAddressIsUsedOnlyBehindAConfiguredTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, List.of("10.0.0.0/8"), 1));
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("10.1.2.3");
        // Two entries for a one-proxy deployment: one proxy appends exactly one, so this chain
        // is longer than anything this configuration can account for and is discarded.
        trusted.addHeader("X-Forwarded-For", "198.51.100.7, 10.2.3.4");
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("192.0.2.10");
        untrusted.addHeader("X-Forwarded-For", "198.51.100.7");

        // The name says "used only behind a configured trusted proxy", so both cases are asserted
        // the same way — on resolveAttribution, which is the value that decides bucketed-or-refused.
        // The old version of this test asserted resolve() on both and so documented the residual
        // fall-open: an untrusted peer got a perfectly respectable-looking address back, with no
        // signal that it was about to be shared with every other caller behind that peer.
        assertThat(resolver.resolveAttribution(trusted).attributable())
                .as("a discarded chain is refused, not bucketed")
                .isFalse();
        assertThat(resolver.resolveAttribution(untrusted).attributable())
                .as("a peer outside the trusted list is refused, not bucketed on its own address")
                .isFalse();

        // The address is still the socket peer, and that is worth asserting separately from the
        // refusal above: it proves the header was discarded for its VALUE, not that the
        // resolver simply returned early without looking.
        assertThat(resolver.resolveAttribution(trusted).clientIp())
                .as("a chain longer than the declared hop count is not used")
                .isEqualTo("10.1.2.3");
        assertThat(resolver.resolveAttribution(untrusted).clientIp())
                .as("the client's forwarded address is ignored, whatever it claims")
                .isEqualTo("192.0.2.10");
    }

    @Test
    @DisplayName("an untrusted peer cannot name its own bucket, and is not bucketed on its own")
    void anUntrustedPeerCannotNameItsOwnBucket() {
        // The invariant behind the prod profile's forward-headers-strategy: none. A public peer
        // that is not in the CIDR list cannot move the resolved address by editing a header, so
        // it cannot walk out of the rate-limit bucket it is already in. ForwardedHeaderFilter
        // breaks exactly this by rewriting getRemoteAddr() before this class ever runs.
        // Loopback is excluded from the list because MockHttpServletRequest arrives from
        // 127.0.0.1, which the shipped application-prod.yml default does contain.
        ClientIpResolver resolver = new ClientIpResolver(
                new ProxyProperties(true, List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"), 1));
        String[] spoofs = {
                "198.51.100.7",
                "10.1.2.3",
                "192.168.5.5",
                "[203.0.113.9]",
                "198.51.100.7, 10.9.9.9, 172.16.0.1",
                "not-an-address",
                ""
        };

        for (String spoof : spoofs) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.200");
            request.addHeader("X-Forwarded-For", spoof);

            assertThat(resolver.resolveAttribution(request).clientIp())
                    .as("X-Forwarded-For: %s", spoof)
                    .isEqualTo("203.0.113.200");
            assertThat(resolver.resolveAttribution(request).attributable())
                    .as("X-Forwarded-For: %s — not choosing a bucket is not the same as having "
                            + "one; this peer gets neither", spoof)
                    .isFalse();
        }
    }
}
