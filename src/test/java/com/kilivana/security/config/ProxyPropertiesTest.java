package com.kilivana.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kilivana.security.ClientIpResolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Pins the guard that makes "proxy trust on, no trusted proxies" undeployable, and proves the
 * answer to the question the deploy checklist asks: the refusal already exists, in
 * {@link ProxyProperties#validate()}, called from {@link ClientIpResolver}'s constructor, so the
 * context cannot come up and no deployment can serve traffic with the setting in that state.
 *
 * <p>No new check was added to {@code DeploymentEnvironmentValidator} for this. The requirement
 * is that an operator cannot deploy in this state, and that is already enforced fail-fast; a
 * second check would be a second place to update the message. What the test does buy is that the
 * guard is not load-bearing only by accident — {@code TRUSTED_PROXIES} is a comma-separated
 * environment variable, and an operator who sets it to {@code ""} or to only separators produces
 * an empty list, which is the case that must refuse.
 */
class ProxyPropertiesTest {

    @Test
    @DisplayName("trusting forwarded headers with no trusted proxies refuses startup")
    void refusesTrustWithoutAnyTrustedProxy() {
        ProxyProperties properties = new ProxyProperties(true, List.of(), 1);

        assertThatThrownBy(() -> new ClientIpResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Trusted proxy addresses are required");
    }

    @ParameterizedTest(name = "TRUSTED_PROXIES={0}")
    @ValueSource(strings = {"", " ", ",", " , , "})
    @DisplayName("a blank or separator-only TRUSTED_PROXIES is an empty list, and refuses startup")
    void refusesAnEmptyTrustedProxyListFromAnyBlankSpelling(String configured) {
        // What relaxed binding produces for an operator who clears the variable in the dashboard.
        ProxyProperties properties = new ProxyProperties(true, Arrays.asList(configured.split(",", -1)), 1);

        assertThat(properties.trustedProxies()).isEmpty();
        assertThatThrownBy(() -> new ClientIpResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Trusted proxy addresses are required");
    }

    @Test
    @DisplayName("an unset trusted-proxy list is treated as empty rather than as a null list")
    void treatsAnUnsetListAsEmpty() {
        assertThat(new ProxyProperties(false, null, 1).trustedProxies()).isEmpty();
    }

    @Test
    @DisplayName("trusting nothing is allowed: it is the safe direction to fail in")
    void allowsTrustingNothingWhenForwardingIsOff() {
        // This is the `TRUST_FORWARDED_HEADERS=false` remediation the diagnostic names, so it has
        // to be a configuration the application accepts.
        assertThatCode(() -> new ClientIpResolver(new ProxyProperties(false, List.of(), 1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("trusting forwarded headers with a trusted proxy is allowed")
    void allowsTrustWithAConfiguredProxy() {
        assertThatCode(() -> new ClientIpResolver(new ProxyProperties(true, List.of("10.0.0.0/8"), 1)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "TRUSTED_PROXIES={0}")
    @ValueSource(strings = {"0.0.0.0/0", "::/0", "0.0.0.0/1", "10.0.0.0/7", "128.0.0.0/1", "fc00::/8"})
    @DisplayName("a trusted-proxy range that trusts everything refuses startup")
    void refusesATrustedProxyRangeThatTrustsEverything(String cidr) {
        // This used to start cleanly. Every peer on the internet matched, so every forwarded
        // header was believed and every caller could name its own rate-limit bucket — a limit
        // that trusts everything limits nothing while still appearing to work. Failing at boot
        // is the only outcome that cannot be mistaken for a working deployment.
        ProxyProperties properties = new ProxyProperties(true, List.of(cidr), 1);

        assertThatThrownBy(() -> new ClientIpResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxies")
                .hasMessageContaining("rate-limit bucket");
    }

    @ParameterizedTest(name = "TRUSTED_PROXIES={0}")
    @ValueSource(strings = {
        "127.0.0.1/32", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "::1/128", "fc00::/7", "fe80::/10", "10.1.2.3"})
    @DisplayName("the narrow ranges a real proxy lives in are all accepted")
    void allowsTheRangesARealProxyActuallyLivesIn(String cidr) {
        assertThatCode(() -> new ClientIpResolver(new ProxyProperties(true, List.of(cidr), 1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the full shipped default list is itself valid")
    void theShippedDefaultListIsValid() {
        // The bound must not be so tight that the configuration this repository ships refuses
        // to start. If it ever is, the deployment is broken in a way no local run would show.
        assertThatCode(() -> new ClientIpResolver(new ProxyProperties(true, List.of(
                        "127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
                        "192.168.0.0/16", "fc00::/7", "fe80::/10"), 1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an over-broad range is refused even when a legitimate one sits beside it")
    void refusesAnOverBroadRangeAlongsideAValidOne() {
        ProxyProperties properties = new ProxyProperties(
                true, List.of("10.0.0.0/8", "0.0.0.0/0"), 1);

        assertThatThrownBy(() -> new ClientIpResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0.0.0.0/0");
    }

    @Test
    @DisplayName("a malformed CIDR prefix is refused rather than silently ignored")
    void refusesAMalformedPrefix() {
        // IpAddressMatcher itself would throw on this, but with a message that names neither
        // the variable nor the consequence. Failing here says which knob is wrong.
        ProxyProperties properties = new ProxyProperties(true, List.of("10.0.0.0/eight"), 1);

        assertThatThrownBy(() -> new ClientIpResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-numeric CIDR prefix");
    }

    @Test
    @DisplayName("a CIDR with more than one slash is refused before it can widen trust")
    void refusesACidrWithMoreThanOneSlash() {
        // Regression, and the nastiest of the validation gaps: the guard reads the prefix with
        // lastIndexOf('/'), but Spring's IpAddressMatcher reads it with a split that drops
        // empty tokens and takes the FIRST segment after the address. So "::/0/24" is checked
        // here as /24 — which passes the >= 16 width rule — and then matched by IpAddressMatcher
        // as /0, which trusts every IPv6 peer on the internet. Startup succeeds and the limiter
        // is off. Only IPv6 is affected: the IPv4 pattern in IpAddressMatcher permits a single
        // slash, so for IPv4 the two readings agree.
        for (String entry : List.of("::/0/24", "::/0/16", "a::/0/24", "fc00::/7/24")) {
            ProxyProperties properties = new ProxyProperties(true, List.of(entry), 1);

            assertThatThrownBy(() -> new ClientIpResolver(properties))
                    .as("entry %s must not boot", entry)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("more than one '/'");
        }
    }

    @Test
    @DisplayName("an impossible hop count stops the boot instead of being coerced")
    void refusesAnImpossibleHopCount() {
        // 0 is the natural way to spell "trust no chain", and coercing it to 1 is fail-open:
        // 1 is precisely the value that accepts one caller-written entry. Fail the boot instead.
        for (int hopCount : List.of(0, -1, 9, 1000)) {
            ProxyProperties properties =
                    new ProxyProperties(true, List.of("10.0.0.0/8"), hopCount);

            assertThatThrownBy(() -> new ClientIpResolver(properties))
                    .as("hop count %s must not boot", hopCount)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("trusted-hop-count");
        }
    }

    @Test
    @DisplayName("the shipped configuration keys actually bind to the record components")
    void shippedKeysBindToTheRecordComponents() {
        // Regression, and the reason this class exists twice over. application-prod.yml
        // declared `trusted-proxy-hops`, which relaxed binding turns into `trustedProxyHops`.
        // The record component is `trustedHopCount`. No such component exists, so the key was
        // silently ignored, the field stayed 0, and trustedHopCount() returned
        // `0 > 0 ? 0 : 1` — which is 1, the shipped default. The knob therefore *looked*
        // wired and behaved correctly, and would have stayed wrong for every deployment that
        // tried to raise it. Nothing fails loudly when a property key matches no component,
        // which is why this asserts binding rather than asserting the accessor.
        new ApplicationContextRunner()
                .withUserConfiguration(ProxyPropertiesBindingConfiguration.class)
                .withPropertyValues(
                        "kilivana.security.proxy.trust-forwarded-headers=true",
                        "kilivana.security.proxy.trusted-proxies=10.0.0.0/8",
                        "kilivana.security.proxy.trusted-hop-count=3")
                .run(context -> {
                    ProxyProperties bound = context.getBean(ProxyProperties.class);
                    assertThat(bound.trustForwardedHeaders()).isTrue();
                    assertThat(bound.trustedProxies()).containsExactly("10.0.0.0/8");
                    assertThat(bound.trustedHopCount())
                            .as("trusted-hop-count must reach the component, not just the default")
                            .isEqualTo(3);
                });
    }

    @EnableConfigurationProperties(ProxyProperties.class)
    static class ProxyPropertiesBindingConfiguration {
    }

    @Test
    @DisplayName("an over-broad range is not evaluated when forwarded headers are off")
    void ignoresRangeWidthWhenTrustIsOff() {
        // The list is inert in this mode, so refusing to boot over it would block a valid,
        // deliberately safe configuration.
        assertThatCode(() -> new ClientIpResolver(new ProxyProperties(false, List.of("0.0.0.0/0"), 1)))
                .doesNotThrowAnyException();
    }
}