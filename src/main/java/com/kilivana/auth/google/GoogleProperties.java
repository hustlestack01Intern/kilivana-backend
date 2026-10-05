package com.kilivana.auth.google;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

/**
 * Configuration for the whole Google sign-in surface, under one flag.
 *
 * <p>{@code enabled} defaults to {@code false} so the feature is off until an operator turns
 * it on, and a bad deploy is turned off by a property change rather than a code deploy. When
 * it is false no Google bean is created at all and the endpoints do not exist.
 *
 * <p>Everything is normalised and range-checked here, at binding time. A value that is wrong in
 * a way that only matters under load — a send budget of zero, a token lifetime of a year —
 * should stop the process at startup with a message naming the property, not be discovered by
 * the first caller.
 */
@ConfigurationProperties(prefix = "kilivana.security.google")
public record GoogleProperties(
        boolean enabled,
        List<String> clientIds,
        List<String> inspectorHostedDomains,
        Duration verificationTokenTtl,
        String frontendUrl,
        Duration onboardingTicketTtl,
        int sendCooldownSeconds,
        int globalSendsPerMinute,
        boolean simulateDelivery) {

    private static final String PRODUCTION_PROFILE = "prod";

    public GoogleProperties {
        clientIds = normalise(clientIds);
        inspectorHostedDomains = normalise(inspectorHostedDomains);
        verificationTokenTtl = verificationTokenTtl == null ? Duration.ofMinutes(15) : verificationTokenTtl;
        onboardingTicketTtl = onboardingTicketTtl == null ? Duration.ofMinutes(10) : onboardingTicketTtl;
        frontendUrl = frontendUrl == null || frontendUrl.isBlank()
                ? "http://localhost:3000/verify-email"
                : frontendUrl.trim();
        sendCooldownSeconds = sendCooldownSeconds <= 0 ? 60 : sendCooldownSeconds;
        globalSendsPerMinute = globalSendsPerMinute <= 0 ? 30 : globalSendsPerMinute;

        if (enabled && clientIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "kilivana.security.google.client-ids must name at least one Google OAuth client id when "
                            + "kilivana.security.google.enabled is true. Without it every ID token would fail "
                            + "the audience check and every Google sign-in would be rejected. Set GOOGLE_CLIENT_ID.");
        }
        if (verificationTokenTtl.compareTo(Duration.ofMinutes(1)) < 0
                || verificationTokenTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "kilivana.security.google.verification-token-ttl must be between one minute and "
                            + "twenty-four hours");
        }
        if (onboardingTicketTtl.compareTo(Duration.ofMinutes(1)) < 0
                || onboardingTicketTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "kilivana.security.google.onboarding-ticket-ttl must be between one minute and one hour");
        }
    }

    private static List<String> normalise(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /**
     * Whether Google's Workspace hosted domain is on the inspector allowlist.
     *
     * <p>A signal for an administrator, nothing more: {@code hd} proves the mailbox belongs to
     * an organisation, never that the person holds a role inside it. Returns false for an
     * absent domain and for an empty allowlist, so an operator who has not configured the list
     * cannot accidentally approve anybody.
     */
    public boolean isInspectorDomainApproved(String hostedDomain) {
        if (hostedDomain == null || hostedDomain.isBlank() || inspectorHostedDomains.isEmpty()) {
            return false;
        }
        return inspectorHostedDomains.contains(hostedDomain.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Simulated delivery hands the verification token back in the HTTP response, which is an
     * account-takeover primitive and exactly the flag a platform leaves on after a local
     * convenience. Refused here rather than in a profile file, because an environment variable
     * outranks every profile file and a profile-based assertion can be overridden into
     * uselessness.
     */
    public void validate(Environment environment) {
        if (simulateDelivery
                && java.util.Arrays.asList(environment.getActiveProfiles()).contains(PRODUCTION_PROFILE)) {
            throw new IllegalStateException("kilivana.security.google.simulate-delivery cannot be enabled on "
                    + "the 'prod' profile: it returns email verification tokens to the caller");
        }
    }
}