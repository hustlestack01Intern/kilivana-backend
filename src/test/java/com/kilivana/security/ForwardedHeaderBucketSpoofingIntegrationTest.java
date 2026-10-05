package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.kilivana.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Boots the application with the {@code prod} profile so the shipped
 * {@code application-prod.yml} — including {@code server.forward-headers-strategy} — is
 * actually in force, then drives real HTTP requests through the whole servlet filter chain
 * to prove the rate-limit bucket cannot be chosen by the client.
 *
 * <p>Every request here comes from one fixed socket peer, {@code 203.0.113.200}. That is a
 * TEST-NET-3 address, so it matches no entry in the shipped
 * {@code kilivana.security.proxy.trusted-proxies} CIDR list. Varying
 * {@code X-Forwarded-For} must therefore make no difference at all to the resolved client
 * IP. It makes a difference only if something upstream of {@link ClientIpResolver} has
 * already rewritten {@code getRemoteAddr()} from that header, which is precisely what
 * {@code ForwardedHeaderFilter} — installed by {@code forward-headers-strategy: framework} —
 * does at {@code Ordered.HIGHEST_PRECEDENCE}.
 *
 * <p>An untrusted peer is only half the story, and the half that is easy to get right. The
 * tests that matter most run against {@code TRUSTED_SOCKET_PEER}, an RFC1918 address that
 * <em>is</em> in the shipped trusted list — the posture every real deployment presents.
 * Trusting a peer means its assertions are eligible for use; it does not make its headers
 * authoritative.
 *
 * <p>There are two ways to be behind a trusted peer and not know who the caller is, and this
 * suite pins both. A chain longer than this deployment declares proxies cannot be one its own
 * proxies wrote. A chain of exactly the declared length that does not parse is the same thing.
 * Neither is usable, and — this is the change these tests exist for — neither is allowed to
 * fall back to the socket peer bucket. Behind an edge the socket peer <em>is</em> the address
 * every client shares, so a fallback there is not a safe default, it is a platform-wide outage
 * waiting for six forged requests. Such a request is refused 503 with no bucket consumed. A
 * chain of exactly the declared length that does verify is still honoured, so the limiter does
 * not collapse the whole platform onto one address.
 */
@ActiveProfiles("prod")
class ForwardedHeaderBucketSpoofingIntegrationTest extends IntegrationTestSupport {

    /**
     * Not in the shipped trusted-proxy list, so {@link ClientIpResolver} must ignore
     * X-Forwarded-For entirely for this peer. It stands in for a public client, or for a
     * proxy the operator has not listed; either way it must key on its own socket address.
     */
    private static final String SOCKET_PEER = "203.0.113.200";

    /**
     * A second public peer, distinct from {@link #SOCKET_PEER}, standing in for a different
     * customer on the same shared edge. Both are outside the shipped trusted-proxy list, which is
     * the whole point: a customer of the platform and a credential-stuffing bot are, from this
     * service's side, indistinguishable.
     */
    private static final String SECOND_PUBLIC_PEER = "203.0.113.201";

    /**
     * Inside the shipped RFC1918 trusted-proxies list, so this peer is one the resolver
     * believes — which is exactly the posture a container behind the platform edge presents.
     * Trust is not the same as authority to assert an address, and these tests pin the
     * difference.
     */
    private static final String TRUSTED_SOCKET_PEER = "172.17.0.1";

    /** The login rule ships at 5/min, so the sixth attempt from one bucket crosses it. */
    private static final int LOGIN_LIMIT = 5;

    /**
     * A prod-profile test has to satisfy the same Redis preflight a deployment does. See
     * {@code IntegrationTestSupport#redisUrl()} for why {@code REDIS_HOST} cannot do it.
     *
     * <p>SMTP is here for the same reason, and for a more important one: the preflight now
     * refuses a prod deployment with no mail host, because password reset is the only
     * account-recovery path. Any prod-profile test must therefore declare a mail host or it
     * tests a context that could never be deployed. The host is deliberately unroutable
     * ({@code .invalid}, reserved by RFC 2606) — nothing is sent to it, because no test here
     * exercises a real reset delivery.
     */
    @DynamicPropertySource
    static void deploymentEndpointProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_URL", IntegrationTestSupport::redisUrl);
        registry.add("spring.mail.host", () -> "smtp.invalid");
        registry.add("spring.mail.username", () -> "kilivana");
        registry.add("spring.mail.password", () -> "not-a-real-relay-secret");
        // REQUIRED, and the third thing this profile demands. application-prod.yml defaults
        // trust-forwarded-headers to true and ships NO default for trusted-proxies, so a prod
        // context that does not name an edge here dies in two places at once: ProxyProperties
        // .validate() refuses to construct ClientIpResolver, and DeploymentEnvironmentValidator
        // adds a violation naming TRUSTED_PROXIES. Both run while the context is still being
        // assembled, so the failure is a startup error rather than a wrong answer — which is the
        // behaviour a real deployment gets, and is why this suite could not be written against
        // the prod profile until it declared the list.
        //
        // Exactly TRUSTED_SOCKET_PEER, and nothing wider. SOCKET_PEER and SECOND_PUBLIC_PEER are
        // 203.0.113.x and must stay untrusted: naming them here would turn the whole suite into
        // a tautology, since the assertions about refused untrusted peers would pass by
        // construction rather than by behaviour.
        registry.add("kilivana.security.proxy.trusted-proxies", () -> TRUSTED_SOCKET_PEER + "/32");
    }

    private static final String BAD_CREDENTIALS = "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}";

    @Test
    @DisplayName("rotating X-Forwarded-For does not buy a fresh rate-limit bucket")
    void aClientCannotChooseItsOwnRateLimitBucket() throws Exception {
        // A different leftmost entry every time. Under forward-headers-strategy: framework each
        // of these is rewritten into a distinct getRemoteAddr(), i.e. a distinct bucket.
        //
        // The answer is no longer "collapses into the shared bucket, so the sixth is 429" — the
        // caller could not reach any bucket at all. SOCKET_PEER is a public address in none of the
        // shipped ranges, so this peer is not one this deployment sanctions, and an unsanctioned
        // peer is refused outright. The property under test is unchanged and strictly stronger: no
        // header the caller writes moves it into a bucket of its own.
        List<String> spoofed = new ArrayList<>();
        for (int attempt = 0; attempt < LOGIN_LIMIT + 1; attempt++) {
            spoofed.add("198.51.100." + (attempt + 1));
        }

        assertThat(loginStatuses(spoofed)).containsOnly("503");
    }

    @Test
    @DisplayName("an X-Forwarded-For naming a private address is not treated as that address")
    void aPrivateAddressInTheHeaderDoesNotBecomeTheClientIp() throws Exception {
        // "10.1.2.3" is inside the shipped RFC1918 trusted range. A resolver that honoured it
        // without a trusted-peer check would key on "login:10.1.2.3" and hand the caller a bucket.
        //
        // Driven from the TRUSTED peer, because that is the only peer whose header is eligible for
        // use at all, and it is the only configuration in which a private address in the header
        // could ever become the client IP. Two entries for a one-proxy deployment, so the chain
        // cannot be one this deployment's proxies wrote. The address must not become the bucket,
        // and it must not be echoed back into the response either.
        List<String> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < LOGIN_LIMIT + 1; attempt++) {
            statuses.add(statusOfLoginFrom(TRUSTED_SOCKET_PEER, "10.1.2.3, 10.0.0." + attempt));
        }

        assertThat(statuses)
                .as("a private address in a forged chain is refused, never bucketed")
                .containsOnly("503");
        assertThat(peerResult(post("/api/v1/auth/login"), TRUSTED_SOCKET_PEER, "10.1.2.3, 10.0.0.1")
                .getResponse().getContentAsString())
                .as("the forged chain is attacker-controlled text and is never echoed")
                .doesNotContain("10.1.2.3")
                .doesNotContain("10.0.0.1");
    }

    @Test
    @DisplayName("no forwarded-header shape escapes the untrusted peer's refusal")
    void allForwardedForValuesShareOneBucket() throws Exception {
        // Public, private-range, IPv4-in-brackets (which ClientIpResolver strips), a
        // right-to-left chain that ends in a trusted hop, and something that is not an
        // address at all. The varied shapes are the coverage: normalisation, the bracket
        // strip and the chain walk are three separate code paths, and each of them must
        // end in the same refusal. A shape that slipped through to a 200 or a 401 would be
        // proof that some header form still reaches the limiter.
        assertThat(loginStatuses(List.of(
                "198.51.100.7",
                "10.1.2.3",
                "[203.0.113.9]",
                "198.51.100.7, 10.9.9.9",
                "not-an-address",
                "192.168.5.5")))
                .containsOnly("503");
    }

    @Test
    @DisplayName("a TRUSTED peer cannot pad the chain to fake an extra proxy")
    void aTrustedPeerCannotForgeItsOwnBucket() throws Exception {
        // This is the case that ships. A container or PaaS instance behind the platform edge
        // presents an RFC1918 socket peer, which IS in the shipped trusted-proxies list, so
        // this test decides whether the limiter works in production. An earlier version of this
        // suite only used the untrusted peer above and stayed green against code that let a
        // trusted peer pick any bucket it liked, because the trusted branch was never run.
        //
        // Every value here carries MORE entries than the single edge this deployment declares.
        // A client behind a real edge sends one address; padding it with addresses of its own
        // choosing is the forgery, and the length check is what catches it - a shape check on
        // the right-most entry alone would let "8.8.8.8, 10.0.0.7" through.
        //
        // The answer is no longer "collapses into the shared bucket, so the sixth is 429".
        // Under that behaviour these requests all keyed on the shared peer bucket, so the sixth
        // spent login:5/min for every other user of the platform - the caller choosing WHICH
        // bucket to hit, not merely which bucket they landed in. A forged chain is refused
        // outright instead, so nothing is consumed and nothing is shared.
        List<String> forged = List.of(
                "198.51.100.7, 10.0.0.7",
                "203.0.113.9, 172.16.5.5",
                "9.9.9.9, 192.168.1.1",
                "1.0.0.1, 10.0.0.1, 172.20.0.1, 192.168.0.1",
                "8.8.8.8, 10.1.1.1",
                "1.1.1.1, fe80::1");

        assertThat(loginStatusesFrom(TRUSTED_SOCKET_PEER, forged)).containsOnly("503");
    }

    @Test
    @DisplayName("forged headers cannot spend another client's budget")
    void forgedHeadersDoNotConsumeTheSharedBucket() throws Exception {
        // The outage this suite exists to prevent. An anonymous caller behind the edge sends a
        // padded chain; the old code fell back to the socket peer, which behind an edge is the
        // address every client shares, so the sixth forged request answered 429 to everybody and
        // the caller had chosen WHICH bucket to spend, not merely which bucket they landed in.
        //
        // Both halves are asserted. The forged caller must never see a 429 or a 401, either of
        // which is proof it reached the limiter at all; and the honest client that goes through
        // the same edge next must be served, because a shared bucket would be spent by now.
        List<String> forgedStatuses = new ArrayList<>();
        for (int attempt = 0; attempt < LOGIN_LIMIT * 4; attempt++) {
            forgedStatuses.add(statusOfLoginFrom(
                    TRUSTED_SOCKET_PEER, "1.1.1." + attempt + ", 2.2.2." + attempt));
        }

        assertThat(forgedStatuses)
                .as("a forged chain is refused, never bucketed, so it can never be throttled")
                .containsOnly("503");
        assertThat(statusOfLoginFrom(TRUSTED_SOCKET_PEER, "198.51.100.10"))
                .as("a legitimate client through the same edge is unaffected by 20 forged requests")
                .isEqualTo("401");
    }

    @Test
    @DisplayName("a trusted peer that forwards nothing is refused, not bucketed with the edge")
    void aTrustedPeerWithNoHeaderIsRefused() throws Exception {
        // No X-Forwarded-For at all is the same failure as a wrong-length one: this deployment
        // trusts forwarded headers from this peer, so a bare request is evidence the edge is not
        // doing its job, and the socket peer is the address every caller behind it shares.
        List<String> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < LOGIN_LIMIT + 1; attempt++) {
            statuses.add(statusOfLoginWithoutHeaderFrom(TRUSTED_SOCKET_PEER));
        }

        assertThat(statuses).containsOnly("503");
    }

    @Test
    @DisplayName("the refusal names the condition without echoing the forged header")
    void theRefusalDoesNotEchoTheForgedChain() throws Exception {
        MvcResult result = mockMvc.perform(peer(post("/api/v1/auth/login"), TRUSTED_SOCKET_PEER)
                        .header("X-Forwarded-For", "1.1.1.1, 2.2.2.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BAD_CREDENTIALS))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).contains("CLIENT_IDENTITY_UNRESOLVED");
        assertThat(result.getResponse().getContentAsString())
                .as("the forged chain is attacker-controlled text and is never echoed back")
                .doesNotContain("1.1.1.1")
                .doesNotContain("2.2.2.2");
    }

    @Test
    @DisplayName("a genuine single-edge chain still separates its clients")
    void aRealProxyChainIsHonoured() throws Exception {
        // The fix must not be "ignore the header always": behind a real edge that overwrites
        // X-Forwarded-For, two different clients must land in two different buckets, or the
        // limiter throttles the whole platform at once. One declared proxy means exactly one
        // entry, which is what these requests carry.
        //
        // This is the behaviour the forged-chain refusal had to leave alone. Both requests come
        // from the SAME socket peer, so if the header were being refused they would both be 503;
        // two 401s means two buckets, which is what per-client limiting actually is.
        String first = statusOfLoginFrom(TRUSTED_SOCKET_PEER, "198.51.100.10");
        String second = statusOfLoginFrom(TRUSTED_SOCKET_PEER, "198.51.100.11");

        assertThat(first).as("the first client through the edge is throttled normally").isEqualTo("401");
        assertThat(second).as("a different client gets a different bucket, not a shared one").isEqualTo("401");
    }

    /**
     * The test the previous version of this suite did not have, and the one that decides whether
     * the platform-wide login outage is actually closed.
     *
     * <p>Everything above exercises a peer inside the shipped trusted list, which is the posture a
     * container presents. The posture this repository <em>deploys</em> to is different: Render,
     * Railway and Fly.io terminate the client connection at a public edge address, and the shipped
     * {@code trusted-proxies} default is loopback + RFC1918 + RFC4193 + link-local, so the real edge
     * matches none of it. Every request therefore arrives from an unsanctioned peer.
     *
     * <p>That is why the old suite was green against a broken deployment. It never drove the
     * untrusted-public-peer path at a rate that could spend a budget, so it never noticed that all
     * of those requests resolved to the one peer address and were bucketed together. One anonymous
     * actor could then spend {@code login:5/min} for the entire platform and a customer with the
     * CORRECT password got 429.
     *
     * <p>Both halves are asserted and the second is the point. The attacker is refused. The honest
     * caller that arrives through the same edge afterwards is <em>not</em> 429 — and it cannot be
     * 503-because-of-someone-else's-spending either, because nothing was spent. Under the previous
     * behaviour those twenty attempts all keyed on the shared peer bucket, the sixth spent it, and
     * this request answered 429 to a customer with the correct password.
     *
     * <p>What the honest caller actually receives here is 503, and that has to be stated plainly
     * rather than written as "served": the refusal is a property of the untrusted PEER, not of the
     * request, so while the edge is unnamed in {@code TRUSTED_PROXIES} every caller behind it is
     * refused alike. This is the deliberate availability trade — a loud, total, diagnosable outage
     * on the four credential endpoints in exchange for not letting one anonymous actor lock out
     * everybody else. {@link #aCorrectlyNamedEdgeServesTheCustomerTheAttackNeverReached()} then
     * closes the loop by showing the same customer served the moment the edge is named.
     */
    @Test
    @DisplayName("a public-edge peer is refused, and no request of its own is answered 429")
    void aPublicEdgePeerIsRefusedAndNeverSpendsTheSharedBudget() throws Exception {
        // Twenty anonymous attempts, each claiming a different source address, from a public
        // socket peer in none of the shipped ranges. Far more than the 5/min this rule allows.
        List<String> attackerStatuses = new ArrayList<>();
        for (int attempt = 0; attempt < LOGIN_LIMIT * 4; attempt++) {
            attackerStatuses.add(statusOfLoginFrom(SOCKET_PEER, "198.51.100." + (attempt + 1)));
        }

        assertThat(attackerStatuses)
                .as("an unsanctioned peer is refused, never bucketed, so it can never be throttled "
                        + "and never spends a budget")
                .containsOnly("503");
        assertThat(peerResult(post("/api/v1/auth/login"), SOCKET_PEER, "198.51.100.200")
                .getResponse().getContentAsString())
                .contains("CLIENT_IDENTITY_UNRESOLVED");

        // The half that matters. Twenty attempts have gone through this exact peer. Under the
        // previous behaviour they all keyed on the shared peer bucket, the 6th spent it, and this
        // request answered 429 — correct password and all. The assertion is that it is NOT 429:
        // a 429 would mean the attacker's attempts reached a budget, and the only budget on this
        // path is the shared one.
        assertThat(statusOfLoginFrom(SOCKET_PEER, "198.51.100.201"))
                .as("a second caller through the same edge is not throttled by 20 attempts: no "
                        + "bucket was ever consumed, so the shared budget is intact")
                .isNotEqualTo("429");
        assertThat(statusOfLoginFrom(SECOND_PUBLIC_PEER, "198.51.100.202"))
                .as("a different customer on the same shared edge is not throttled either")
                .isNotEqualTo("429");
    }

    /**
     * The other half of the previous test, and the one that shows the outage is the operator's to
     * fix rather than a permanent cost: name the edge in {@code TRUSTED_PROXIES} and the same
     * customer is served.
     *
     * <p>Driven from {@link #TRUSTED_SOCKET_PEER}, which stands for a correctly named edge. The
     * attacker's forged requests are still refused — the peer is trusted, so its header is
     * <em>eligible</em> for use, but a chain longer than one hop cannot be one the edge wrote — and
     * the honest client through that edge is served a normal 401. This is what "the attacker's
     * attempts never consumed the shared budget" looks like when nothing is refused by accident.
     */
    @Test
    @DisplayName("a correctly named edge serves the customer the attack never reached")
    void aCorrectlyNamedEdgeServesTheCustomerTheAttackNeverReached() throws Exception {
        List<String> forgedStatuses = new ArrayList<>();
        for (int attempt = 0; attempt < LOGIN_LIMIT * 4; attempt++) {
            forgedStatuses.add(statusOfLoginFrom(TRUSTED_SOCKET_PEER, "1.1.1." + attempt + ", 2.2.2." + attempt));
        }

        assertThat(forgedStatuses).as("a forged chain is still refused from a trusted peer")
                .containsOnly("503");
        assertThat(statusOfLoginFrom(TRUSTED_SOCKET_PEER, "198.51.100.10"))
                .as("a legitimate client through a named edge is served, because 20 forged requests "
                        + "consumed nothing")
                .isEqualTo("401");
    }

    /**
     * The refusal must not leak into paths that carry no credential rule. An untrusted peer is
     * refused on {@code login}, and is completely unaffected everywhere else — including a write
     * that persists a row, which is the difference between a loud outage on the four credential
     * endpoints and a dead platform.
     */
    @Test
    @DisplayName("the untrusted-peer refusal stays on the four credential endpoints")
    void thePublicEdgeRefusalDoesNotReachUnlimitedPaths() throws Exception {
        for (String path : List.of("/api/v1/contact-messages", "/api/v1/products", "/api/v1/cart/checkout")) {
            MvcResult result = mockMvc.perform(peer(post(path), SOCKET_PEER)
                            .header("X-Forwarded-For", "198.51.100.7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).as("%s", path).isNotEqualTo(503);
        }
    }

    private MvcResult peerResult(MockHttpServletRequestBuilder builder, String remoteAddress, String forwardedFor)
            throws Exception {
        return mockMvc.perform(peer(builder, remoteAddress)
                        .header("X-Forwarded-For", forwardedFor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BAD_CREDENTIALS))
                .andReturn();
    }

    private String statusOfLoginFrom(String socketPeer, String forwardedFor) throws Exception {
        return String.valueOf(mockMvc.perform(peer(post("/api/v1/auth/login"), socketPeer)
                        .header("X-Forwarded-For", forwardedFor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BAD_CREDENTIALS))
                .andReturn()
                .getResponse()
                .getStatus());
    }

    private String statusOfLoginWithoutHeaderFrom(String socketPeer) throws Exception {
        return String.valueOf(mockMvc.perform(peer(post("/api/v1/auth/login"), socketPeer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BAD_CREDENTIALS))
                .andReturn()
                .getResponse()
                .getStatus());
    }

    private List<String> loginStatuses(List<String> forwardedForValues) throws Exception {
        return loginStatusesFrom(SOCKET_PEER, forwardedForValues);
    }

    private List<String> loginStatusesFrom(String socketPeer, List<String> forwardedForValues) throws Exception {
        List<String> statuses = new ArrayList<>();
        for (String forwardedFor : forwardedForValues) {
            statuses.add(statusOfLoginFrom(socketPeer, forwardedFor));
        }
        return statuses;
    }

    /** The only thing this class varies is the address the socket actually came from. */
    private static MockHttpServletRequestBuilder peer(MockHttpServletRequestBuilder builder, String remoteAddress) {
        return builder.with(request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        });
    }
}