package com.kilivana.security.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.security.proxy")
public record ProxyProperties(boolean trustForwardedHeaders, List<String> trustedProxies, int trustedHopCount) {

    /**
     * The number of trusted proxies in front of this application. Defaults to 1, the topology
     * this repository ships for: exactly one platform edge.
     *
     * <p>This is the security-critical knob and 1 is the only safe default. A forwarded chain
     * is believable only if its length matches the number of proxies actually in front of this
     * service, because each one appends exactly one entry. Trusting a header of the wrong
     * length is what lets a caller behind the edge choose its own rate-limit bucket: it
     * appends a private address it invented and the shape looks right. Counting is the only
     * check that catches a missing entry and an extra forged one alike.
     */
    public int trustedHopCount() {
        return trustedHopCount > 0 ? trustedHopCount : 1;
    }

    /**
     * No plausible topology has more than a handful of proxies in front of this service. A
     * value beyond that cannot be a miscount, it is a mistake, and it matches no real chain —
     * which pushes every request onto the socket peer and, behind an edge, onto one shared
     * bucket for the whole platform.
     */
    private static final int MAX_TRUSTED_HOP_COUNT = 8;

    /**
     * Ranges that are private by definition and so are always safe to trust at whatever width
     * they are defined: a container, a VM or a host on the same private network is exactly the
     * thing an operator means when they configure a proxy, and none of these ranges is
     * routable on the public internet, so no internet caller can present itself as one.
     *
     * <p>Width alone cannot express this. {@code fc00::/7} and {@code fe80::/10} are the IPv6
     * equivalents of RFC 1918 and are legitimately wide; a numeric prefix bound wide enough to
     * admit them would also admit {@code 2000::/3}, which is the entire public internet.
     */
    private static final Set<String> PRIVATE_RANGES = Set.of(
            "127.0.0.0/8",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "::1/128",
            "fc00::/7",
            "fe80::/10");

    /**
     * Widest CIDR permitted for anything that is not a recognised private range. A proxy is a
     * specific host or a small network, so anything broader than this in public address space
     * is a configuration that cannot mean what it says. IPv4 {@code /8} and IPv6 {@code /16}
     * are the bounds.
     */
    private static final int MIN_IPV4_PREFIX = 8;
    private static final int MIN_IPV6_PREFIX = 16;

    /**
     * Normalises the trusted-proxy list: an unset value becomes an empty list rather than null,
     * and blank entries are dropped. Relaxed binding hands this record whatever the operator
     * typed, so {@code TRUSTED_PROXIES=""} and {@code TRUSTED_PROXIES=" , , "} both arrive as
     * lists of blanks that would otherwise pass a null check and match nothing.
     */
    public ProxyProperties {
        trustedProxies = trustedProxies == null
                ? List.of()
                : trustedProxies.stream().filter(proxy -> proxy != null && !proxy.isBlank()).toList();
    }

    public void validate() {
        if (trustForwardedHeaders && trustedProxies.isEmpty()) {
            throw new IllegalStateException("Trusted proxy addresses are required when forwarded headers are enabled");
        }
        if (trustForwardedHeaders && (trustedHopCount < 1 || trustedHopCount > MAX_TRUSTED_HOP_COUNT)) {
            // Coercing this to 1 would be fail-open: 0 is the natural way to spell "trust no
            // chain at all", and quietly turning it into 1 is the one value that accepts a
            // single caller-written entry. A nonsense value must stop the boot instead.
            throw new IllegalStateException("kilivana.security.proxy.trusted-hop-count is "
                    + trustedHopCount + "; it must be between 1 and " + MAX_TRUSTED_HOP_COUNT
                    + ", and must equal the number of proxies that append to X-Forwarded-For in "
                    + "front of this service");
        }
        if (!trustForwardedHeaders) {
            return;
        }
        for (String proxy : trustedProxies) {
            validateNotTooBroad(proxy);
        }
    }

    /**
     * Fails startup rather than silently deploying an ineffective limiter. A bad
     * {@code TRUSTED_PROXIES} is otherwise indistinguishable at runtime from a working one:
     * the filter keeps answering, it just stops limiting the people it was meant to limit.
     */
    private void validateNotTooBroad(String proxy) {
        String trimmed = proxy.trim().toLowerCase(Locale.ROOT);
        if (PRIVATE_RANGES.contains(trimmed)) {
            return;
        }
        int firstSlash = trimmed.indexOf('/');
        if (firstSlash < 0) {
            // A bare address is the tightest possible entry; nothing to bound.
            return;
        }
        // The guard and the matcher must agree on which number is the prefix, or this check is
        // decoration. IpAddressMatcher splits on '/', drops empty tokens and reads the FIRST
        // segment after the address; this code reads the LAST. On any entry with two slashes
        // the two disagree, and the matcher wins at runtime. Refuse the shape outright rather
        // than try to mirror someone else's parser.
        if (trimmed.indexOf('/', firstSlash + 1) >= 0) {
            throw new IllegalStateException("kilivana.security.proxy.trusted-proxies entry '" + proxy
                    + "' has more than one '/'; write a single address or CIDR such as 10.0.0.0/8");
        }
        int slash = firstSlash;
        String suffix = trimmed.substring(slash + 1);
        int prefix;
        try {
            prefix = Integer.parseInt(suffix);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "kilivana.security.proxy.trusted-proxies entry '" + proxy
                            + "' has a non-numeric CIDR prefix; use an address or CIDR such as 10.0.0.0/8");
        }
        boolean ipv6 = trimmed.substring(0, slash).contains(":");
        int minimum = ipv6 ? MIN_IPV6_PREFIX : MIN_IPV4_PREFIX;
        int maximum = ipv6 ? 128 : 32;
        if (prefix < 0 || prefix > maximum || prefix < minimum) {
            throw new IllegalStateException("kilivana.security.proxy.trusted-proxies entry '" + proxy
                    + "' trusts " + (prefix < 0 ? "an invalid amount of" : "too much of")
                    + " the public address space. Every peer it matches will have its "
                    + "X-Forwarded-For believed, so any of them can choose its own rate-limit "
                    + "bucket. The widest accepted outside the private ranges ("
                    + String.join(", ", PRIVATE_RANGES.stream().sorted().toList())
                    + ") is /" + minimum + " for " + (ipv6 ? "IPv6" : "IPv4")
                    + "; name the specific proxy host instead.");
        }
    }
}
