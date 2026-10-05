package com.kilivana.security;

import com.kilivana.security.config.ProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /**
     * How long a named peer stays named, and how many peers are held in memory doing it.
     *
     * <p>This replaced a cap of eight distinct peers with no expiry, under which the ninth peer of
     * a multi-address platform edge produced no output for the remaining life of the process. See
     * {@link DiagnosticBudget} for why the bound is a wall clock and why the memory bound evicts
     * rather than refuses.
     */
    private static final long DIAGNOSTIC_INTERVAL_MILLIS = 60_000L;

    /**
     * Large enough that a platform edge fleet fits inside it without eviction, small enough that
     * the per-peer entry costs less than a line of log does: an address and a long, a few tens of
     * kilobytes in the worst case.
     */
    private static final int MAX_TRACKED_PEERS = 256;

    private final ProxyProperties properties;
    private final List<IpAddressMatcher> trustedProxies;

    /**
     * Separate budgets, because these are separate failures with opposite remedies and an operator
     * must be able to read one without the other. They previously shared a single capped set, so
     * eight peers' worth of unverifiable chains was enough to silence the bucket-collapse warning
     * permanently — the one that names {@code TRUSTED_PROXIES}.
     */
    private final DiagnosticBudget bucketCollapseBudget;
    private final DiagnosticBudget unverifiableChainBudget;

    /**
     * One-shot latch for the "proxy trust is off" notice. Not keyed by peer, unlike the budgets
     * above: it is a statement about how this process is configured, so it is said once per
     * process however many peers and requests pass through. Volatile rather than atomic because
     * the only requirement is that it flips, and at worst two request threads racing the first
     * login each say it once.
     */
    private volatile boolean trustDisabledReported;

    /**
     * Annotated because the class has a second, package-private constructor for tests. Spring only
     * auto-selects when exactly one constructor is visible to it; with two and no annotation, bean
     * creation fails outright with "No default constructor found". That failure was invisible for as
     * long as no test could compile, because it only appears when a Spring context actually tries
     * to instantiate this class.
     */
    @Autowired
    public ClientIpResolver(ProxyProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * The clock is injected so a test can hold time still and observe a whole interval's worth of
     * behaviour in microseconds. Without it the time-based budget could only be tested by sleeping,
     * and a test that sleeps is a test that gets skipped.
     */
    ClientIpResolver(ProxyProperties properties, Clock clock) {
        properties.validate();
        this.properties = properties;
        this.trustedProxies = properties.trustedProxies().stream()
                .map(IpAddressMatcher::new)
                .toList();
        this.bucketCollapseBudget =
                new DiagnosticBudget(clock, DIAGNOSTIC_INTERVAL_MILLIS, MAX_TRACKED_PEERS);
        this.unverifiableChainBudget =
                new DiagnosticBudget(clock, DIAGNOSTIC_INTERVAL_MILLIS, MAX_TRACKED_PEERS);
    }

    /**
     * The outcome of one resolution, and — the reason this record exists — whether the resolved
     * address is attributable to a single client at all.
     *
     * <p>A rate-limit bucket keyed on the socket peer is only meaningful when the socket peer is
     * one client. Behind a PaaS edge it is the edge, and every caller behind it shares one bucket
     * per rule, so six forged requests from an anonymous caller spend the credential budget of the
     * entire platform. {@code clientIp} alone cannot express that, because in the collapse case it
     * is a perfectly valid IP address that is simply not the caller's. {@code reason} is a stable
     * machine token and never contains any part of the attacker-controlled header.
     */
    public record Resolution(String clientIp, boolean attributable, String reason) {
    }

    /**
     * The socket peer is the client: no trusted proxy is in front to forward for one. This is the
     * ONLY configuration in which an untrusted peer is attributable, and it is reachable only when
     * {@code trust-forwarded-headers} is false.
     */
    private static final String REASON_TRUST_DISABLED = "forwarded-headers-untrusted";

    /**
     * The one reason token consumed outside this class: {@link RateLimitFilter} has to choose
     * between two opposite remedies — name this peer in {@code TRUSTED_PROXIES}, versus count
     * the proxies that append to the header — and it cannot tell them apart from
     * {@code clientIp} alone. Public because the choice is made in another class, not because the
     * token is a general API.
     */
    public static final String REASON_PEER_NOT_TRUSTED = "peer-not-trusted";

    private static final String REASON_NO_HEADER = "no-x-forwarded-for";
    private static final String REASON_HOP_COUNT_MISMATCH = "hop-count-mismatch";
    private static final String REASON_UNPARSEABLE_CHAIN = "unparseable-chain";
    private static final String REASON_VERIFIED_CHAIN = "verified-chain";

    /**
     * The only way to learn a client address. There is deliberately no
     * {@code String resolve(HttpServletRequest)} beside it.
     *
     * <p>That accessor returned the resolved address and nothing else, which is precisely the
     * information a caller needs and precisely the information that is unsafe to act on. Behind a
     * PaaS edge the collapse case yields a perfectly valid IP that is not the caller's, with no
     * signal that anything is wrong — so any code reaching for it reintroduces the shared bucket
     * that {@link RateLimitFilter} exists to prevent, and it compiles cleanly. It had no
     * production callers and roughly a dozen test call sites, all of which pinned the collapse
     * value as though it were an answer. {@link Resolution#attributable()} cannot be ignored by
     * accident because it sits on the same object as the address.
     *
     * @see Resolution#attributable()
     */
    public Resolution resolveAttribution(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        boolean headerPresent = forwardedFor != null && !forwardedFor.isBlank();
        if (!properties.trustForwardedHeaders()) {
            reportTrustDisabled();
            return new Resolution(remoteAddress, true, REASON_TRUST_DISABLED);
        }
        if (!isTrusted(remoteAddress)) {
            // Reach here only when trust is ON, which is the operator declaring that a proxy IS in
            // front of this service. A peer outside the trusted list is therefore traffic from a hop
            // this deployment did not sanction — either the edge is missing from TRUSTED_PROXIES, or
            // a caller reached this service directly and bypassed the edge. In BOTH cases the socket
            // peer is not the client.
            //
            // This is the branch that matters in production. The shipped trusted list is loopback +
            // RFC1918 + RFC4193 + link-local, and Render/Railway/Fly.io edges terminate at PUBLIC
            // addresses, so on the platform this repository actually deploys to every request lands
            // here. The previous behaviour returned attributable=true, which meant the platform-wide
            // login budget was reachable by anyone: five anonymous attempts from one actor spent
            // login:5/min for every other user, and a correct password still answered 429.
            //
            // DELIBERATE AVAILABILITY TRADE, and a reviewer must be able to see it. A deployment that
            // enables proxy trust without correctly naming its edge now answers 503
            // CLIENT_IDENTITY_UNRESOLVED on all four credential endpoints, instead of serving them
            // out of one shared bucket. That is the intent: a loud, diagnosable, log-named outage
            // beats a quiet one where one anonymous actor locks out every customer. The remedy is to
            // add the peer to TRUSTED_PROXIES — never to turn trust off, which reintroduces the
            // collapse and additionally silences this warning (see reportTrustDisabled).
            //
            // Reported whether or not the request carried X-Forwarded-For. The previous version
            // gated this on the header being present, which meant an edge that forwards nothing at
            // all produced no line here — and the filter's line for that case pointed at
            // TRUSTED_PROXY_HOPS, which is not the cause here and not the remedy either. The cause
            // is the same in both branches: this peer is not in TRUSTED_PROXIES.
            reportBucketCollapse(remoteAddress, headerPresent);
            return new Resolution(remoteAddress, false, REASON_PEER_NOT_TRUSTED);
        }
        if (!headerPresent) {
            // A trusted peer that forwarded nothing gave this service no evidence of any client
            // behind it, so the peer address is all there is — and behind an edge that is the
            // address every client shares. The caller is refused rather than bucketed with them.
            return new Resolution(remoteAddress, false, REASON_NO_HEADER);
        }
        String[] candidates = forwardedFor.split(",");
        String fallback = remoteAddress;

        // A proxy that proxies APPENDS: it rewrites the header as "<what it received>, <address
        // it saw the connection from>". So a genuine chain's length is exactly the number of
        // trusted hops in front of this application, and every entry from the right up to that
        // many positions is one this deployment's own proxies wrote.
        //
        // Counting is what makes the chain verifiable. Checking only that the right-most entry
        // is trusted is not enough, because an attacker who can reach a trusted peer can write
        // "<anything>, <a private address>" and satisfy that shape — they then choose the
        // rate-limit bucket, which disables all four credential limits at once. Requiring the
        // header to be exactly `trustedHopCount` long means a forger must guess that count, and
        // an extra forged element overshoots it just as a missing one does.
        //
        // The count is taken on the RAW split, before any per-entry normalisation. This resolver
        // runs pre-authentication on every request, including the ones it is about to throttle,
        // so it is not a place to spend work on a header that is already the wrong length.
        // Tomcat allows 8KB of header, which is ~2700 comma-separated tokens; normalising all
        // of them (a regex and an InetAddress.getByName each) before rejecting the chain turns
        // a request the limiter was going to refuse anyway into milliseconds of CPU. Counting
        // first is both cheaper and stricter: dropping unparseable entries could only ever make
        // a too-long chain look like the right length, which is the one thing worth preventing.
        if (candidates.length != properties.trustedHopCount()) {
            reportUnverifiableForwardedChain(remoteAddress, candidates.length);
            return new Resolution(remoteAddress, false, REASON_HOP_COUNT_MISMATCH);
        }

        List<String> chain = new ArrayList<>();
        for (String candidate : candidates) {
            String normalized = normalizeAddress(candidate);
            if (normalized != null) {
                chain.add(normalized);
            }
        }
        if (chain.size() != properties.trustedHopCount()) {
            // The raw length was right and normalisation still lost something, so at least one
            // entry was not an address this service can read. Same consequence as a wrong length:
            // the chain is not one this deployment's proxies wrote.
            reportUnverifiableForwardedChain(remoteAddress, chain.size());
            return new Resolution(remoteAddress, false, REASON_UNPARSEABLE_CHAIN);
        }

        for (int index = chain.size() - 1; index >= 0; index--) {
            String candidate = chain.get(index);
            fallback = candidate;
            if (!isTrusted(candidate)) {
                return new Resolution(candidate, true, REASON_VERIFIED_CHAIN);
            }
        }
        return new Resolution(fallback, true, REASON_VERIFIED_CHAIN);
    }

    /**
     * Names a trusted peer whose forwarded chain carries no evidence that it proxied at all,
     * once. Distinct from {@link #reportBucketCollapse} because the buckets are not collapsing
     * — they are forgeable, which is the opposite failure and needs the opposite remedy.
     */
    private void reportUnverifiableForwardedChain(String peerAddress, int forwardedForCount) {
        if (!unverifiableChainBudget.shouldReport(peerAddress)) {
            return;
        }
        log.warn("Discarding X-Forwarded-For from socket peer {}: the header holds {} usable "
                        + "address(es) but this deployment is configured for exactly {} trusted "
                        + "proxy hop(s), so the chain cannot be one our own proxies wrote. Either "
                        + "a client is writing the header itself, or an untrusted hop is forging "
                        + "one. The request is REFUSED with 503 CLIENT_IDENTITY_UNRESOLVED and no "
                        + "bucket is consumed: falling back to the socket peer here would mean "
                        + "keying on the edge, which merges every caller behind it into one bucket "
                        + "per rule. If this peer really is the only proxy in front of this "
                        + "application, TRUSTED_PROXY_HOPS=1 is correct; raise it once per additional "
                        + "proxy. The client's forwarded address is not logged.",
                peerAddress, forwardedForCount, properties.trustedHopCount());
    }

    /**
     * Names the peer that is collapsing every client into one rate-limit bucket, and keeps naming
     * it for as long as it keeps happening.
     *
     * <p>{@code headerPresent} only changes what the first sentence says. It used to decide whether
     * the line was emitted at all, and that is what this signature exists to correct: an edge that
     * forwards nothing and an edge whose header is discarded have the same cause and the same
     * remedy, and the second shape is the one a misconfigured edge actually produces.
     *
     * <p>The client's forwarded address is deliberately absent from the message. It is
     * attacker-controlled, unbounded in length, and logging it would let anyone write arbitrary
     * text into the operator's log by sending a header. The peer address is the only value that
     * has to be copied into {@code TRUSTED_PROXIES}, so it is the only one printed.
     */
    private void reportBucketCollapse(String peerAddress, boolean headerPresent) {
        if (!bucketCollapseBudget.shouldReport(peerAddress)) {
            return;
        }
        long suppressed = bucketCollapseBudget.takeSuppressedIfDue();
        if (suppressed > 0) {
            log.error("CLIENT_IDENTITY_UNRESOLVED is STILL happening for {} socket peer(s) ({}) "
                            + "about {} of them still in the previous minute. The remedy is unchanged "
                            + "and is not a hop count: add each peer address to "
                            + "kilivana.security.proxy.trusted-proxies. Every 503 on "
                            + "POST /api/v1/auth/login, /signup, /register, /forgot-password and "
                            + "/api/v1/admins/signup with reason=peer-not-trusted is this condition. "
                            + "Until it is fixed, no one can sign in.",
                    bucketCollapseBudget.trackedKeyCount(), peerAddress, suppressed);
        }
        log.warn("Rate-limit buckets would collapse onto one client address: request arrived from "
                + "socket peer {}, which is not in kilivana.security.proxy.trusted-proxies"
                + (headerPresent
                        ? ", so the X-Forwarded-For it sent is being discarded"
                        : ", and it forwarded no X-Forwarded-For at all") + ". "
                + "PROXY TRUST IS ON, which means this deployment expects a proxy in front of it, so "
                + "this peer is either the edge that is missing from TRUSTED_PROXIES or a caller that "
                + "bypassed the edge. Either way it is not the client, and keying on it would give "
                + "every caller behind it ONE bucket per rule for the whole platform (login, signup, "
                + "forgot-password and admin-bootstrap), so one abusive actor could spend the "
                + "credential budget of every other user. The request is therefore REFUSED with 503 "
                + "CLIENT_IDENTITY_UNRESOLVED and no bucket is consumed. The fix is to add this "
                + "peer's address or CIDR to TRUSTED_PROXIES. Do NOT turn TRUST_FORWARDED_HEADERS off "
                + "instead: that reinstates the shared bucket for every caller AND silences this very "
                + "warning, so the collapse then happens with nothing in the log to show for it. The "
                + "client's forwarded address is not logged.",
                peerAddress);
    }

    /**
     * Says once that proxy trust is off, so "trust off" is never the silent state.
     *
     * <p>This existed as an inference gap and is now a closed one. {@code docs/deploy/railway.md}
     * told operators that the ABSENCE of the collapse warning proved they were safe — but this
     * branch returns before {@link #reportBucketCollapse} is ever reached, so under
     * {@code TRUST_FORWARDED_HEADERS=false} that warning could not fire at all, and its absence was
     * the only possible outcome. Absence of evidence was being read as evidence of absence. With
     * this notice present, the two configurations are distinguishable from the log alone, which is
     * the precondition for any first-deploy check built on the log.
     *
     * <p>Attributable requests still flow in this configuration, and that is correct: with no proxy
     * the socket peer genuinely is the client. The notice exists because this is also the
     * configuration under which a shared edge silently merges every caller, and an operator cannot
     * see that from the outside.
     */
    private void reportTrustDisabled() {
        if (trustDisabledReported) {
            return;
        }
        trustDisabledReported = true;
        log.warn("kilivana.security.proxy.trust-forwarded-headers is false, so no peer is trusted and "
                + "X-Forwarded-For is ignored on every request. Every caller is therefore bucketed on "
                + "its own socket address. That is correct ONLY when there is genuinely no proxy in "
                + "front of this service. Behind a PaaS edge the socket peer is the edge itself, so "
                + "every customer shares ONE bucket per rule and one abusive actor can lock out the "
                + "whole platform. If a proxy IS in front, set TRUST_FORWARDED_HEADERS=true and name "
                + "it in TRUSTED_PROXIES; leaving it off is what makes the collapse invisible.");
    }

    private boolean isTrusted(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    }

    private boolean isIpv4(String address) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    private String normalizeAddress(String value) {
        if (value == null) {
            return null;
        }
        String address = value.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        int zoneIndex = address.indexOf('%');
        if (zoneIndex >= 0) {
            address = address.substring(0, zoneIndex);
        }
        if (!address.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        if (!address.contains(":") && !isIpv4(address)) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
