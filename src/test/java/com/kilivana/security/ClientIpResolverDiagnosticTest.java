package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kilivana.security.config.ProxyProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pins the deploy-time diagnostic for the one production risk nobody can resolve from the
 * repository: whether the platform's edge proxy connects from an address inside the shipped
 * trusted-proxy list.
 *
 * <p>{@code application-prod.yml} ships loopback + RFC1918 + RFC4193 + link-local, and
 * {@code TRUST_FORWARDED_HEADERS} defaults to true. If Railway's edge proxy arrives from one of
 * those ranges every client gets its own bucket and the deployment is correct. If it arrives
 * from anywhere else, {@link ClientIpResolver} discards {@code X-Forwarded-For} for every
 * request, all clients key on the proxy address, and the four credential limits become global
 * budgets — an immediate, total, self-inflicted login outage that looks like a rate-limit bug
 * and is not one. **Nobody has deployed this to a real Railway project**, so the two cases are
 * indistinguishable from the outside. The only honest resolution is to say which one is
 * happening, loudly, once.
 *
 * <p>These are unit tests on the resolver rather than on {@code RateLimitFilter} because the
 * decision is made here and nowhere else: {@code ClientIpResolver.resolve} has exactly one
 * caller and it is the filter.
 */
class ClientIpResolverDiagnosticTest {

    /** Outside the trusted list in every fixture below, so it stands in for the platform edge. */
    private static final String UNTRUSTED_PEER = "203.0.113.200";

    /** Inside the {@code 10.0.0.0/8} list every fixture below trusts. */
    private static final String TRUSTED_PEER = "10.1.2.3";

    /** The client's own address as the proxy asserts it. Must never reach the log. */
    private static final String FORWARDED_CLIENT = "198.51.100.77";

    private static final List<String> TRUSTED_RANGES = List.of("10.0.0.0/8");

    /**
     * The shape a single edge produces: an edge that overwrites X-Forwarded-For leaves exactly
     * one entry per proxy, so with {@code trustedHopCount = 1} this is the genuine case.
     */
    private static final String APPENDED_CHAIN_BY_PROXY = FORWARDED_CLIENT;

    private Logger resolverLogger;
    private ListAppender<ILoggingEvent> warnings;

    @BeforeEach
    void captureResolverWarnings() {
        resolverLogger = (Logger) LoggerFactory.getLogger(ClientIpResolver.class);
        warnings = new ListAppender<>();
        warnings.start();
        resolverLogger.addAppender(warnings);
        // The resolver must be free to log at WARN for this test to mean anything; it is not set
        // to WARN anywhere in this repository, but inheriting root's level would otherwise make
        // this suite pass for the wrong reason on a machine with a debug logback.xml.
        resolverLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void releaseResolverLogger() {
        resolverLogger.detachAppender(warnings);
        warnings.stop();
        resolverLogger.setLevel(null);
    }

    @Test
    @DisplayName("an untrusted peer carrying X-Forwarded-For is reported, naming the refusal it causes")
    void reportsTheUntrustedPeerCarryingAForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));

        assertThat(resolution.clientIp())
                .as("the untrusted peer's own address is all that was observed")
                .isEqualTo(UNTRUSTED_PEER);
        assertThat(resolution.attributable())
                .as("and the address is reported AS a refusal, not as an answer to bucket on")
                .isFalse();
        assertThat(resolution.reason()).isEqualTo(ClientIpResolver.REASON_PEER_NOT_TRUSTED);

        assertThat(warnings.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .as("the operator has to be told which address to add")
                    .contains(UNTRUSTED_PEER)
                    .contains("TRUSTED_PROXIES")
                    .as("and what the symptom would be if the peer were bucketed")
                    .contains("ONE bucket per rule");
        });
    }

    @Test
    @DisplayName("the warning never recommends disabling trust as a remedy for the collapse")
    void theWarningDoesNotRecommendDisablingTrust() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));

        // The old text ended with "or set TRUST_FORWARDED_HEADERS=false to accept the collapse
        // knowingly". Following that instruction produced ZERO log output, because the
        // trust-disabled branch returns at ClientIpResolver.java:87 before any diagnostic is
        // reached — so the operator silenced the only evidence they had and the collapse
        // continued, unnoticed and now undocumented. The remedy has to be a real one: name the
        // peer in TRUSTED_PROXIES.
        assertThat(warnings.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .doesNotContain("TRUST_FORWARDED_HEADERS=false")
                        .doesNotContain("TRUST_FORWARDED_HEADERS=false to accept")
                        .doesNotContain("accept the collapse knowingly"));
    }

    @Test
    @DisplayName("the client's forwarded address never reaches the log")
    void theClientSuppliedAddressIsNotLogged() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));

        // The header is attacker-controlled and unbounded: logging it hands anyone who can send
        // a request the ability to write arbitrary text into the operator's deploy log.
        assertThat(warnings.list)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain(FORWARDED_CLIENT)
                        .doesNotContain("198.51.100"));
    }

    @Test
    @DisplayName("a trusted peer that appended its own address gets its client's real bucket")
    void trustsAGenuineProxyChain() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(TRUSTED_PEER, APPENDED_CHAIN_BY_PROXY));

        assertThat(resolution.clientIp())
                .as("a proxy that appended proves it proxied, so the client address is usable")
                .isEqualTo(FORWARDED_CLIENT);
        assertThat(resolution.attributable())
                .as("and it is safe to key on: one verified hop per declared proxy, so this "
                        + "address belongs to a single client")
                .isTrue();
        // The only branch that yields a client address while trust is ON. Asserting the token
        // pins that this case took the verified path rather than any of the four refusals above
        // it, which would also return the peer or some other address by coincidence.
        assertThat(resolution.reason()).isEqualTo("verified-chain");

        assertThat(warnings.list).isEmpty();
    }

    @Test
    @DisplayName("a chain longer than the configured hop count is discarded")
    void refusesAChainLongerThanTheHopCount() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        // Two entries where one proxy was declared. An edge that overwrites X-Forwarded-For
        // always produces exactly one entry, so a second one means either a client appended to
        // it or a second, unconfigured proxy is in the path. Either way this deployment cannot
        // account for what wrote it, and the header is dropped in favour of the socket peer.
        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(TRUSTED_PEER, FORWARDED_CLIENT + ", 10.9.9.9"));

        assertThat(resolution.clientIp())
                .as("the peer is the only thing actually observed, so it is what is reported")
                .isEqualTo(TRUSTED_PEER);
        assertThat(resolution.attributable())
                .as("but it is REFUSED, not bucketed: keying here would merge every caller behind "
                        + "this peer into one bucket, which is the outcome the hop check exists to "
                        + "prevent. This is the assertion the old String-only form could not make.")
                .isFalse();
        assertThat(resolution.reason()).isEqualTo("hop-count-mismatch");
    }

    @Test
    @DisplayName("a chain shorter than the configured hop count is discarded")
    void refusesAChainShorterThanTheHopCount() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 2));

        // The operator declared two proxies; a one-entry header means one of them is not
        // appending, so the value is not what this configuration says it is.
        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(TRUSTED_PEER, FORWARDED_CLIENT));

        assertThat(resolution.clientIp()).isEqualTo(TRUSTED_PEER);
        // Direction of the error carries no information about trustworthiness: too short is
        // refused exactly as too long is, because both mean the chain is not one this
        // deployment's own proxies wrote.
        assertThat(resolution.attributable()).isFalse();
        assertThat(resolution.reason()).isEqualTo("hop-count-mismatch");
    }

    @Test
    @DisplayName("the hop count is what makes an appended chain trustworthy")
    void theHopCountDistinguishesRealChainsFromForgeries() {
        // Documented boundary, stated here so it is not mistaken for an oversight. With one
        // trusted proxy declared, a single-entry header from a trusted peer is exactly what a
        // real edge produces, and is indistinguishable from a client that wrote one itself. No
        // in-process check can separate those two cases — only the edge can, by overwriting
        // X-Forwarded-For rather than appending to it, which Render, Railway and Fly.io all
        // do. Hop counting is what protects every multi-proxy deployment; the single-edge case
        // rests on the platform's overwrite behaviour, which is why TRUSTED_PROXY_HOPS must be
        // raised the moment a second proxy is introduced.
        ClientIpResolver oneHop = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));
        assertThat(oneHop.resolveAttribution(requestFrom(TRUSTED_PEER, APPENDED_CHAIN_BY_PROXY)).clientIp())
                .as("one declared proxy, one entry: this is the shape a real edge emits")
                .isEqualTo(FORWARDED_CLIENT);
        assertThat(oneHop.resolveAttribution(requestFrom(TRUSTED_PEER, APPENDED_CHAIN_BY_PROXY)).attributable())
                .as("and it is the client's own bucket, not the shared one behind the proxy")
                .isTrue();

        ClientIpResolver twoHops = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 2));
        assertThat(twoHops.resolveAttribution(
                requestFrom(TRUSTED_PEER, FORWARDED_CLIENT + ", " + TRUSTED_PEER)).clientIp())
                .as("two declared proxies, two entries: still a verifiable chain")
                .isEqualTo(FORWARDED_CLIENT);
    }

    @Test
    @DisplayName("the unverifiable chain is reported once per peer, and names the peer")
    void reportsAnUnverifiableChainOnce() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        for (int attempt = 0; attempt < 25; attempt++) {
            resolver.resolveAttribution(requestFrom(TRUSTED_PEER, FORWARDED_CLIENT + ", 10.9.9.9"));
        }

        assertThat(warnings.list).hasSize(1);
        assertThat(warnings.list.get(0).getFormattedMessage())
                .contains(TRUSTED_PEER)
                .contains("TRUSTED_PROXY_HOPS");
        assertThat(warnings.list.get(0).getFormattedMessage())
                .as("the client-supplied address must never be logged: it is attacker-controlled text")
                .doesNotContain(FORWARDED_CLIENT);
    }

    /**
     * The same defect class as the collapse warning, in the sibling message. It used to say
     * "Buckets are keyed on the socket peer instead, which is safe but shares one bucket per rule"
     * — advice describing behaviour that no longer exists, since a wrong-length chain from a
     * trusted peer is refused rather than bucketed. An operator reading it would have been told to
     * accept a shared bucket on a path where nothing is bucketed at all.
     */
    @Test
    @DisplayName("the unverifiable-chain warning does not claim the request is being bucketed")
    void theUnverifiableChainWarningDoesNotClaimBucketing() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        resolver.resolveAttribution(requestFrom(TRUSTED_PEER, FORWARDED_CLIENT + ", 10.9.9.9"));

        assertThat(warnings.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("REFUSED")
                    .doesNotContain("Buckets are keyed on the socket peer instead")
                    .doesNotContain("which is safe");
        });
    }

    @Test
    @DisplayName("the collapse is reported once per peer, not once per request")
    void reportsEachCollapsingPeerOnlyOnce() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        for (int attempt = 0; attempt < 25; attempt++) {
            resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));
        }
        resolver.resolveAttribution(requestFrom("198.51.100.9", FORWARDED_CLIENT));

        assertThat(warnings.list)
                .as("25 login attempts must not become 25 log lines, and a second bad peer must "
                        + "still be named")
                .hasSize(2);
        assertThat(warnings.list.get(1).getFormattedMessage()).contains("198.51.100.9");
    }

    /**
     * CHANGED MEANING, and the reason is in {@code reportBucketCollapse} rather than here.
     *
     * <p>This used to assert silence, and it was right to: the warning used to be emitted only
     * when an {@code X-Forwarded-For} was present and being discarded. That gate is gone. It is
     * now reported whether or not the header arrived, because an edge that forwards nothing at
     * all and an edge whose header is discarded have the <em>same</em> cause — this peer is not
     * in {@code TRUSTED_PROXIES} — and the same remedy. Gating on the header meant the operator
     * with the worst case, a misconfigured edge emitting no header, got no line from this class
     * and was sent by the filter's message to {@code TRUSTED_PROXY_HOPS}, which is not the cause
     * here and cannot be the remedy either.
     *
     * <p>So the assertion below is inverted, not deleted: it is now stronger than silence, and it
     * pins that the single line distinguishes the two shapes by name. The peer is still refused
     * ({@code attributable} is false), which is the part that matters for bucketing.
     */
    @Test
    @DisplayName("an untrusted peer with no X-Forwarded-For is still reported, and the line says so")
    void anAbsentHeaderIsStillReported() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, null));

        assertThat(resolution.clientIp()).isEqualTo(UNTRUSTED_PEER);
        assertThat(resolution.attributable())
                .as("an absent header from an untrusted peer is refused, not treated as a direct "
                        + "client: trust is ON, so the operator declared a proxy is in front")
                .isFalse();
        assertThat(resolution.reason()).isEqualTo(ClientIpResolver.REASON_PEER_NOT_TRUSTED);

        assertThat(warnings.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .as("the cause and the remedy are the same as when a header WAS discarded: "
                            + "this peer is missing from TRUSTED_PROXIES either way")
                    .contains(UNTRUSTED_PEER)
                    .contains("TRUSTED_PROXIES")
                    // The one sentence that differs between the two shapes, and the reason the
                    // operator can tell which one they are looking at.
                    .contains("it forwarded no X-Forwarded-For at all")
                    .doesNotContain("being discarded");
        });
    }

    @Test
    @DisplayName("trust off is never silent: it says every caller is bucketed on its socket address")
    void reportsThatProxyTrustIsDisabled() {
        ClientIpResolver resolver =
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1));

        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));

        // Trust off is the one configuration in which an untrusted peer IS attributable, because
        // there is genuinely no proxy and the socket peer really is the client. This is what makes
        // "turn trust off" an invalid remedy for the collapse above, and it is why the notice
        // below has to exist: the resolver cannot tell the operator that this configuration is
        // silently merging every caller behind a shared edge.
        assertThat(resolution.clientIp()).isEqualTo(UNTRUSTED_PEER);
        assertThat(resolution.attributable()).isTrue();

        // "Trust off" is a real configuration, not an accident, and it is exactly the one under
        // which every caller behind a shared edge collapses into a single bucket. It used to log
        // nothing at all, which is what made docs/deploy/railway.md tell operators that the
        // ABSENCE of the collapse warning proved they were safe. Following that inference is how
        // a deployment ends up silently collapsed. The notice has to be there for the absence of
        // the other warning to mean anything.
        assertThat(warnings.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .as("the operator must learn what this configuration does, from the log")
                    .contains("TRUST_FORWARDED_HEADERS")
                    .contains("TRUSTED_PROXIES")
                    .contains("shares");
        });
    }

    @Test
    @DisplayName("the trust-disabled notice is emitted once, not once per request")
    void theTrustDisabledNoticeIsNotPerRequest() {
        ClientIpResolver resolver =
                new ClientIpResolver(new ProxyProperties(false, List.of(), 1));

        for (int attempt = 0; attempt < 50; attempt++) {
            resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));
            resolver.resolveAttribution(requestFrom(TRUSTED_PEER, FORWARDED_CLIENT));
        }

        // It is a statement about configuration, not about a peer, so one line per process is
        // the whole story. Fifty login attempts must not become fifty lines.
        assertThat(warnings.list).hasSize(1);
    }

    /**
     * {@code attributable} is the field that decides whether a credential request is bucketed or
     * refused, and it is false in every case where a configured proxy failed to give this service
     * a client address. That combination — a trusted peer in front, and nothing verifiable
     * behind it — is precisely the case in which the socket peer is the platform edge and every
     * caller shares one bucket.
     */
    @Test
    @DisplayName("a trusted peer that forwards nothing leaves the request unattributable")
    void anAbsentHeaderFromATrustedPeerIsUnattributable() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        ClientIpResolver.Resolution absent = resolver.resolveAttribution(requestFrom(TRUSTED_PEER, null));
        ClientIpResolver.Resolution blank = resolver.resolveAttribution(requestFrom(TRUSTED_PEER, "   "));

        assertThat(absent.attributable()).as("no header: the peer is all there is").isFalse();
        assertThat(absent.reason()).isEqualTo("no-x-forwarded-for");
        assertThat(absent.clientIp()).isEqualTo(TRUSTED_PEER);
        assertThat(blank.attributable()).isFalse();
        assertThat(blank.reason()).isEqualTo("no-x-forwarded-for");
    }

    @Test
    @DisplayName("a wrong-length chain from a trusted peer is unattributable, whatever the direction of the error")
    void aWrongLengthChainFromATrustedPeerIsUnattributable() {
        ClientIpResolver oneHop = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));
        ClientIpResolver twoHops = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 2));

        ClientIpResolver.Resolution tooLong =
                oneHop.resolveAttribution(requestFrom(TRUSTED_PEER, FORWARDED_CLIENT + ", 10.9.9.9"));
        ClientIpResolver.Resolution tooShort =
                twoHops.resolveAttribution(requestFrom(TRUSTED_PEER, FORWARDED_CLIENT));

        assertThat(tooLong.attributable()).isFalse();
        assertThat(tooLong.reason()).isEqualTo("hop-count-mismatch");
        assertThat(tooLong.clientIp())
                .as("unchanged from today: the peer is what is actually observed")
                .isEqualTo(TRUSTED_PEER);
        assertThat(tooShort.attributable()).isFalse();
        assertThat(tooShort.reason()).isEqualTo("hop-count-mismatch");
    }

    @Test
    @DisplayName("a right-length chain that does not parse is unattributable")
    void anUnparseableChainFromATrustedPeerIsUnattributable() {
        // Two entries for a two-proxy deployment, one of which is not an address at all. The raw
        // count is right, so the hop check passes; normalisation then drops the bad entry and the
        // usable chain is one hop short. Calling this attributable would hand the caller the
        // shared bucket, which is the exact outcome the hop check exists to prevent.
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 2));

        ClientIpResolver.Resolution resolution = resolver.resolveAttribution(
                requestFrom(TRUSTED_PEER, FORWARDED_CLIENT + ", not-an-address"));

        assertThat(resolution.attributable()).isFalse();
        assertThat(resolution.reason()).isEqualTo("unparseable-chain");
        assertThat(resolution.clientIp()).isEqualTo(TRUSTED_PEER);
    }

    /**
     * The load-bearing assertion of this whole file.
     *
     * <p>When {@code trust-forwarded-headers} is true, the operator is declaring that a proxy is
     * in front of this service. A peer that is not in the trusted list is therefore traffic from a
     * hop this deployment did not sanction: either the edge is missing from
     * {@code TRUSTED_PROXIES}, or a caller reached this service directly and bypassed the edge.
     * Either way the socket peer is not the client, so keying on it merges every caller behind it
     * into one bucket. Calling that attributable is what made the platform-wide login outage
     * reachable on Render and Railway, where the edge terminates at a public address and no
     * shipped CIDR range matches.
     */
    @Test
    @DisplayName("with trust on, a peer outside the trusted list is unattributable")
    void anUntrustedPeerWithTrustOnIsUnattributable() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));

        assertThat(resolution.attributable())
                .as("a peer this deployment does not sanction is not the client")
                .isFalse();
        assertThat(resolution.reason()).isEqualTo("peer-not-trusted");
        assertThat(resolution.clientIp())
                .as("unchanged: the peer is all that was actually observed")
                .isEqualTo(UNTRUSTED_PEER);
    }

    @Test
    @DisplayName("with trust OFF a peer outside the trusted list is still attributable")
    void anUntrustedPeerWithTrustOffIsAttributable() {
        // The deliberate exception. When there is genuinely no proxy, the socket peer IS the
        // client and the socket peer is the best answer this service has. This is the one
        // configuration in which "not in the trusted list" carries no information at all, which
        // is exactly why turning trust OFF cannot be a remedy for the collapse above.
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(false, List.of(), 1));

        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));

        assertThat(resolution.attributable()).isTrue();
        assertThat(resolution.reason()).isEqualTo("forwarded-headers-untrusted");
        assertThat(resolution.clientIp()).isEqualTo(UNTRUSTED_PEER);
    }

    @Test
    @DisplayName("a verified chain is attributable; the trust-off path is attributable")
    void everyAttributableCaseSaysSo() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 1));

        ClientIpResolver.Resolution verified = resolver.resolveAttribution(
                requestFrom(TRUSTED_PEER, APPENDED_CHAIN_BY_PROXY));
        ClientIpResolver.Resolution trustOff = new ClientIpResolver(new ProxyProperties(false, List.of(), 1))
                .resolveAttribution(requestFrom(UNTRUSTED_PEER, FORWARDED_CLIENT));

        assertThat(verified.attributable()).isTrue();
        assertThat(verified.reason()).isEqualTo("verified-chain");
        assertThat(verified.clientIp()).isEqualTo(FORWARDED_CLIENT);
        assertThat(trustOff.attributable()).isTrue();
        assertThat(trustOff.reason()).isEqualTo("forwarded-headers-untrusted");
    }

    @Test
    @DisplayName("the reason never carries the client-supplied address")
    void theReasonNeverCarriesTheHeaderText() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, TRUSTED_RANGES, 2));
        String forged = "not-an-address-at-all";

        ClientIpResolver.Resolution resolution =
                resolver.resolveAttribution(requestFrom(TRUSTED_PEER, FORWARDED_CLIENT + ", " + forged));

        // The reason travels to a log line on the login path. It is a fixed token, so a caller
        // cannot write arbitrary text into an operator's log by sending a header.
        assertThat(resolution.reason())
                .isEqualTo("unparseable-chain")
                .doesNotContain(forged)
                .doesNotContain(FORWARDED_CLIENT)
                .doesNotContain("198.51.100");
    }

    private static MockHttpServletRequest requestFrom(String peerAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peerAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}