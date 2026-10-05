package com.kilivana.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Refuses to start under the {@code prod} profile unless the deployment is actually
 * configured, and says exactly which environment variable to set for each thing that is
 * wrong. Every violation is collected and reported together: an operator fixing a
 * deployment wants the whole list in one restart, not one failure per attempt.
 *
 * <p>Read from {@link Environment} at construction rather than from a bound properties
 * record because the values are secrets that must never be echoed. An environment
 * variable outranks every property file, so the check has to see the final resolved
 * value, not what a profile file intended.
 */
public final class DeploymentEnvironmentValidator {

    private static final String PRODUCTION_PROFILE = "prod";

    /**
     * Profile names an operator might plausibly type in place of {@code prod}. None of them
     * load {@code application-prod.yml}, and all of them look correct on a dashboard, which
     * is exactly why they are rejected rather than tolerated.
     */
    private static final Set<String> PROFILE_ALIASES = Set.of("production", "prod-aws", "live");

    /**
     * Values that exist in this repository's local tooling. They are fine on a laptop and
     * catastrophic in production: a known JWT signing key means anyone can mint an admin
     * token for the account that knows it, and a known bootstrap key means anyone can
     * create the first admin.
     *
     * <p>Long, distinctive literals are matched as substrings because an operator may have
     * prefixed or suffixed one. Short generic words are matched only in full: "secret"
     * occurs by chance in roughly one base64 secret in twelve, and matching it as a
     * substring would refuse a valid {@code openssl rand -base64 32} output about 9% of the
     * time.
     */
    private static final List<String> KNOWN_PLACEHOLDER_FRAGMENTS = List.of(
            // docker-compose.yml, before the secrets were removed from it.
            "local-development-secret-at-least-32-characters-long",
            "local-development-admin-bootstrap-key",
            "your-super-secret-jwt-key-min-32-chars-long-for-hs256",
            "change-me-in-production",
            // scripts/run-dev.sh.
            "dev-only-secret-change-me-4f3f2d1c",
            "kilivana-dev-bootstrap-key",
            // .github/workflows/ci-cd.yml. A pipeline value is still a value this repository
            // has published, and these leak into a service the moment somebody copies them.
            "test-secret-key-minimum-32-characters-long-for-hs256",
            "test-bootstrap-key",
            // README.md's quick start, before it became an openssl command. Both spellings,
            // because the shorter one is long enough to clear the length floor on its own and
            // would otherwise boot clean.
            "change-me-to-a-long-random-secret-min-32-bytes",
            "change-me-to-a-long-random-secret",
            "some-long-bootstrap-key",
            // src/test/java/com/kilivana/support/IntegrationTestSupport.java.
            //
            // "test-admin-bootstrap-key" is deliberately absent: that is the live fixture
            // prefix five integration tests sign up through, not a leaked production value.
            // Denying it made the prod-profile spoofing test refuse to start.
            "test-secret-key-that-is-at-least-thirty-two-bytes-long");

    private static final List<String> KNOWN_PLACEHOLDER_VALUES = List.of(
            "change-me",
            "changeme",
            "change_me",
            "secret",
            "supersecret",
            "please-change-me");

    private static final int MINIMUM_SECRET_LENGTH = 32;

    /**
     * The admin bootstrap key gates {@code POST /api/v1/admins/signup} and is the only thing
     * between a stranger and the first admin account, so it carries the same floor as the JWT
     * signing key. Unlike the JWT secret there is nothing behind it: knowing the key is
     * knowing the account.
     */
    private static final int MINIMUM_ADMIN_BOOTSTRAP_KEY_LENGTH = 32;

    /**
     * Names a control that does not exist, in both spellings an operator can reach.
     *
     * <p>{@code RATE_LIMIT_REQUESTS_PER_MINUTE} binds to nothing because {@code application.yml}
     * no longer declares the key. {@code KILIVANA_SECURITY_RATE_LIMIT_REQUESTS_PER_MINUTE} is
     * the relaxed-binding form of the same dead property and resolves to
     * {@code kilivana.security.rate-limit.requests-per-minute}, which no record component
     * claims. They are the same promise, so both are refused. Kept as constants so the
     * message, the docs and the test cannot drift apart on the spelling.
     */
    private static final List<String> GENERAL_RATE_LIMIT_VARIABLES = List.of(
            "RATE_LIMIT_REQUESTS_PER_MINUTE",
            "KILIVANA_SECURITY_RATE_LIMIT_REQUESTS_PER_MINUTE");

    private static final String REDIS_URL_VARIABLE = "REDIS_URL";
    private static final String REDIS_HOST_VARIABLE = "REDIS_HOST";

    /**
     * The trusted-proxy list, and the flag that decides whether it is read at all. Read as bound
     * property names rather than as raw variable names so that any binding form an operator
     * reaches for — {@code TRUSTED_PROXIES}, the relaxed-binding
     * {@code KILIVANA_SECURITY_PROXY_TRUSTED_PROXIES}, {@code SPRING_APPLICATION_JSON} — is
     * accepted, rather than only the one spelling this class happens to know.
     */
    private static final String TRUST_FORWARDED_HEADERS_PROPERTY =
            "kilivana.security.proxy.trust-forwarded-headers";
    private static final String TRUSTED_PROXIES_PROPERTY = "kilivana.security.proxy.trusted-proxies";

    private final Environment environment;

    public DeploymentEnvironmentValidator(Environment environment) {
        this.environment = environment;
    }

    /**
     * Returns {@code true} when the {@code prod} profile is active. A profile can be
     * supplied as {@code prod}, {@code production} is a different profile and does not
     * count: the settings this guards are the ones {@code application-prod.yml} ships.
     */
    public boolean applies() {
        return Arrays.asList(environment.getActiveProfiles()).contains(PRODUCTION_PROFILE);
    }

    /**
     * Collects every violation. Returns an empty list when the deployment is sound.
     * Secret values are never interpolated; only their length and a placeholder hint are.
     */
    public List<String> violations() {
        List<String> violations = new ArrayList<>();
        checkProfileName(violations);
        checkJwtSecret(violations);
        checkAdminBootstrapKey(violations);
        checkSimulatedPasswordResetDelivery(violations);
        checkRateLimitBackend(violations);
        checkGeneralRateLimitVariables(violations);
        checkMediaRoot(violations);
        checkRedisConfiguration(violations);
        checkTrustedProxies(violations);
        checkPasswordResetMail(violations);
        checkGoogleSignIn(violations);
        return List.copyOf(violations);
    }

    /**
     * A profile that <em>looks</em> like production but is not {@code prod} is the most likely
     * operator mistake on this list, and it fails completely silently. {@code production} is a
     * different profile to {@code prod}, so {@code application-prod.yml} never loads: forwarded
     * headers silently fall back to disabled (every client collapses into one rate-limit
     * bucket, so the four credential limits become a platform-wide budget), {@code MEDIA_ROOT}
     * reverts to a relative path inside the container so uploads vanish on every deploy, and
     * none of these checks run at all. Failing loudly here converts an outage into a startup
     * error naming the actual fix.
     */
    private void checkProfileName(List<String> violations) {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            return;
        }
        for (String profile : active) {
            String normalized = profile.trim().toLowerCase(java.util.Locale.ROOT);
            if (PROFILE_ALIASES.contains(normalized)) {
                violations.add("SPRING_PROFILES_ACTIVE contains '" + profile + "' but the production "
                        + "profile is named '" + PRODUCTION_PROFILE + "'. application-" + profile
                        + ".yml does not exist, so every production setting silently does not "
                        + "apply: forwarded headers stay off (one shared rate-limit bucket for all "
                        + "users), MEDIA_ROOT reverts to a relative path and uploads are lost on "
                        + "every deploy, and no deployment check runs. Use " + PRODUCTION_PROFILE
                        + " exactly.");
                return;
            }
        }
    }

    /**
     * Unconfigured SMTP previously failed silently and looked healthy, because Boot's mail
     * health indicator is off by default and the platform's health check only ever saw green.
     * Password reset is the only account-recovery path, so this is a lockout, not a cosmetic
     * gap.
     *
     * <p>The mail health indicator is deliberately left off rather than switched on here: an
     * unreachable relay would then make {@code /actuator/health} return 503 and pull the whole
     * API out of rotation, which is a far worse failure than a broken reset link. Catching
     * misconfiguration at startup, where the deploy fails with a message naming the variable,
     * is the correct layer for it.
     */
    private void checkPasswordResetMail(List<String> violations) {
        Object host = environment.getProperty("spring.mail.host");
        Object username = environment.getProperty("spring.mail.username");
        Object password = environment.getProperty("spring.mail.password");
        boolean smtpConfigured = host != null && !host.toString().isBlank();
        boolean credentialsConfigured =
                username != null && !username.toString().isBlank() && password != null && !password.toString().isBlank();
        if (!smtpConfigured) {
            violations.add("spring.mail.host is not set, so password reset cannot deliver. Every "
                    + "reset request would fail at send time, and password reset is the only "
                    + "way a user can recover a lost account. Set MAIL_HOST (plus "
                    + "MAIL_USERNAME/MAIL_PASSWORD for an authenticated relay) before this "
                    + "instance takes traffic.");
            return;
        }
        if (!credentialsConfigured) {
            violations.add("spring.mail.username/password are not both set. Some relays require "
                    + "authentication and will reject every send, which fails password reset "
                    + "silently. Set both, or clear them deliberately if this relay is open.");
        }
    }

    /**
     * Checks the Google surface only when it is switched on.
     *
     * <p>Three things are load-bearing and none of them is observable at runtime in a way an
     * operator would notice: an empty audience list rejects every ID token, an unreachable mail
     * relay makes every unproven-address signup silently undeliverable, and simulated delivery
     * returns the verification token in the HTTP response. Each of those is a refusal at
     * startup here rather than a support ticket.
     *
     * <p>The audience and the simulated-delivery flag are also checked at binding time, in
     * {@code GoogleProperties}. This repeats them because a violation must appear in the one
     * message that lists every deployment problem, and because the two checks fail differently:
     * binding time cannot see an environment variable that relaxed binding never connected.
     */
    private void checkGoogleSignIn(List<String> violations) {
        if (!environment.getProperty("kilivana.security.google.enabled", Boolean.class, Boolean.FALSE)) {
            return;
        }
        String clientIds = trimmed("kilivana.security.google.client-ids");
        if (clientIds == null || clientIds.isBlank()) {
            violations.add("kilivana.security.google.enabled is true but "
                    + "kilivana.security.google.client-ids is empty. Every ID token would fail its "
                    + "audience check and every Google sign-in would be refused as invalid. Set "
                    + "GOOGLE_CLIENT_ID.");
        }
        if (environment.getProperty("kilivana.security.google.simulate-delivery",
                Boolean.class, Boolean.FALSE)) {
            violations.add("kilivana.security.google.simulate-delivery is enabled: "
                    + "remove it, or set "
                    + "KILIVANA_SECURITY_GOOGLE_SIMULATE_DELIVERY=false. Only that relaxed-binding "
                    + "form reaches this property. The flag returns email verification tokens in "
                    + "the HTTP response, which is account takeover: whoever can complete one "
                    + "onboarding owns the address.");
        }
        Object host = environment.getProperty("spring.mail.host");
        if (host == null || host.toString().isBlank()) {
            violations.add("kilivana.security.google.enabled is true but spring.mail.host is "
                    + "not set. Signups on any address Google does not vouch for would be told to "
                    + "check their mail and never receive anything. Set MAIL_HOST (plus "
                    + "MAIL_USERNAME/MAIL_PASSWORD for an authenticated relay).");
        }
    }

    /**
     * Throws once, with the complete list, rather than on the first problem.
     */
    public void validate() {
        List<String> violations = violations();
        if (violations.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(
                "Deployment preflight failed for the 'prod' profile (")
                .append(violations.size())
                .append(violations.size() == 1 ? " problem" : " problems")
                .append("):");
        for (String violation : violations) {
            message.append(System.lineSeparator()).append("  - ").append(violation);
        }
        throw new IllegalStateException(message.toString());
    }

    private void checkJwtSecret(List<String> violations) {
        String secret = trimmed("kilivana.security.jwt.secret");
        if (secret == null) {
            violations.add("kilivana.security.jwt.secret is not set: set JWT_SECRET to the output of "
                    + "`openssl rand -base64 32` (at least " + MINIMUM_SECRET_LENGTH + " characters). "
                    + "Tokens signed with a missing or known key can be forged.");
            return;
        }
        if (secret.length() < MINIMUM_SECRET_LENGTH) {
            // The length is safe to print. The value is not.
            violations.add("kilivana.security.jwt.secret is only " + secret.length() + " characters: "
                    + "set JWT_SECRET to at least " + MINIMUM_SECRET_LENGTH
                    + " characters (`openssl rand -base64 32`).");
            return;
        }
        if (isPlaceholder(secret)) {
            violations.add("kilivana.security.jwt.secret looks like a local placeholder value: "
                    + "set JWT_SECRET to a fresh `openssl rand -base64 32` value. The value is known "
                    + "to anyone with this repository.");
        }
    }

    private void checkAdminBootstrapKey(List<String> violations) {
        String key = trimmed("kilivana.security.admin.bootstrap-key");
        if (key == null) {
            violations.add("kilivana.security.admin.bootstrap-key is not set: set ADMIN_BOOTSTRAP_KEY to at "
                    + "least " + MINIMUM_ADMIN_BOOTSTRAP_KEY_LENGTH + " characters "
                    + "(`openssl rand -hex 24`), otherwise POST /api/v1/admins/signup cannot create the "
                    + "first admin.");
            return;
        }
        // Placeholder is checked before length on purpose. A key this repository has published
        // is worse news than a short one, and several of the historical values are short, so
        // checking length first would report the milder problem for the more dangerous input.
        if (isPlaceholder(key)) {
            violations.add("kilivana.security.admin.bootstrap-key looks like a local placeholder value: "
                    + "set ADMIN_BOOTSTRAP_KEY to a fresh `openssl rand -hex 24` value. A known bootstrap "
                    + "key lets anyone claim the first admin account.");
            return;
        }
        if (key.length() < MINIMUM_ADMIN_BOOTSTRAP_KEY_LENGTH) {
            // The length is safe to print. The value is not.
            violations.add("kilivana.security.admin.bootstrap-key is only " + key.length() + " characters: "
                    + "set ADMIN_BOOTSTRAP_KEY to at least " + MINIMUM_ADMIN_BOOTSTRAP_KEY_LENGTH
                    + " characters (`openssl rand -hex 24`).");
        }
    }

    private void checkSimulatedPasswordResetDelivery(List<String> violations) {
        if (environment.getProperty("kilivana.security.password-reset.simulate-delivery",
                Boolean.class, Boolean.FALSE)) {
            violations.add("kilivana.security.password-reset.simulate-delivery is enabled: "
                    + "remove it, or set KILIVANA_SECURITY_PASSWORD_RESET_SIMULATE_DELIVERY=false. "
                    + "Only that relaxed-binding form reaches this property; the bare name "
                    + "PASSWORD_RESET_SIMULATE_DELIVERY is wired to nothing. The flag "
                    + "returns password reset tokens in the HTTP response, which is account takeover.");
        }
    }

    private void checkRateLimitBackend(List<String> violations) {
        String backend = trimmed("kilivana.security.rate-limit.backend");
        if ("redis".equals(backend == null ? null : backend.toLowerCase(Locale.ROOT))) {
            return;
        }
        violations.add("kilivana.security.rate-limit.backend is "
                + (backend == null ? "not set" : "'" + backend + "'")
                + ": set RATE_LIMIT_BACKEND=redis. The memory backend keeps one counter per "
                + "application instance, so the effective limit on a horizontally scaled "
                + "deployment is the configured limit multiplied by the replica count, and each "
                + "restart hands every client a fresh allowance.");
    }

    /**
     * Read from the raw variable name, not from a bound property: the
     * {@code RATE_LIMIT_REQUESTS_PER_MINUTE} placeholder is gone from {@code application.yml},
     * so nothing binds it and nothing would notice an operator leaving it set. Setting it
     * was always a no-op — the general rate limit it fed has no rule in
     * {@code RateLimitFilter} and never did — so the variable is a promise the application
     * does not keep. Refuse to start rather than let an operator believe they are protected.
     */
    private void checkGeneralRateLimitVariables(List<String> violations) {
        for (String variable : GENERAL_RATE_LIMIT_VARIABLES) {
            if (environment.getProperty(variable) == null) {
                continue;
            }
            violations.add(variable + " is set: remove it from the service variables. The general "
                    + "rate limit is not implemented — RateLimitFilter applies rules only to login, "
                    + "signup/register, forgot-password and admin-bootstrap — so this variable does "
                    + "nothing, in either spelling. Only those four credential-abuse limits are "
                    + "enforced, and they come from RATE_LIMIT_LOGIN_PER_MINUTE, "
                    + "RATE_LIMIT_REGISTER_PER_MINUTE, RATE_LIMIT_FORGOT_PERMINUTE and "
                    + "RATE_LIMIT_ADMIN_BOOTSTRAP_PER_MINUTE. Check both names are gone: "
                    + String.join(" and ", GENERAL_RATE_LIMIT_VARIABLES) + ".");
        }
    }

    /**
     * A Redis rate limiter that cannot reach Redis answers {@code UNAVAILABLE} and the filter
     * turns that into a 503 on login, signup, register, forgot-password and admin-signup. All
     * five fail closed with no diagnostic anywhere, because the backend check is satisfied by
     * the mere word {@code redis} and {@code application.yml} quietly supplies
     * {@code localhost} as the host — so the preflight reported zero problems on a service
     * where nobody could sign in. Refuse to start instead.
     */
    private void checkRedisConfiguration(List<String> violations) {
        if (redisUrlIsConfigured()) {
            return;
        }
        String host = trimmed(REDIS_HOST_VARIABLE);
        if (host == null) {
            violations.add(REDIS_URL_VARIABLE + " is not set and " + REDIS_HOST_VARIABLE
                    + " is not set, so Redis falls back to localhost:6379. Set " + REDIS_URL_VARIABLE
                    + " to the provider's redis:// URI, or set " + REDIS_HOST_VARIABLE
                    + " to the managed instance's hostname. Without a reachable Redis every login, "
                    + "signup, register, forgot-password and admin-signup request answers 503.");
            return;
        }
        if (isLoopback(host)) {
            violations.add(REDIS_HOST_VARIABLE + "=" + host + " is a loopback address and "
                    + REDIS_URL_VARIABLE + " is not set, so the limiter shares one Redis with "
                    + "whatever else happens to run on this host. Set " + REDIS_URL_VARIABLE
                    + " to the provider's redis:// URI, or set " + REDIS_HOST_VARIABLE
                    + " to the managed instance's hostname.");
        }
    }

    private boolean redisUrlIsConfigured() {
        String url = trimmed(REDIS_URL_VARIABLE);
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("redis://") || lower.startsWith("rediss://");
    }

    private boolean isLoopback(String host) {
        String address = host.trim().toLowerCase(Locale.ROOT);
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        return address.startsWith("127.")
                || address.equals("localhost")
                || address.equals("::1")
                || address.equals("0:0:0:0:0:0:0:1");
    }

    private void checkMediaRoot(List<String> violations) {
        String root = trimmed("kilivana.media.root-dir");
        if (root == null) {
            // kilivana.media.root-dir is bound to ${MEDIA_ROOT:...}, so this only fires when
            // the property has been overridden wholesale. Fall back to the raw variable.
            root = environment.getProperty("MEDIA_ROOT");
        }
        if (root == null || root.isBlank()) {
            violations.add("kilivana.media.root-dir is not set: set MEDIA_ROOT to an absolute path such "
                    + "as /data/uploads. Unset, uploads are written to the container filesystem and lost "
                    + "on every deploy.");
            return;
        }
        Path directory;
        try {
            directory = Paths.get(root).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            violations.add("kilivana.media.root-dir is not a usable path: fix the MEDIA_ROOT value.");
            return;
        }
        if (!Files.isDirectory(directory)) {
            Path parent = directory.getParent();
            if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
                violations.add("MEDIA_ROOT=" + root + " does not exist and cannot be created: mount a "
                        + "volume at " + root + " or make the parent directory writable.");
                return;
            }
            try {
                Files.createDirectories(directory);
            } catch (Exception exception) {
                violations.add("MEDIA_ROOT=" + root + " does not exist and could not be created: "
                        + exception.getClass().getSimpleName() + ". Mount a volume at " + root + ".");
                return;
            }
        }
        if (!Files.isWritable(directory)) {
            violations.add("MEDIA_ROOT=" + root + " exists but is not writable by the process user ("
                    + System.getProperty("user.name", "unknown")
                    + "): set RAILWAY_RUN_UID=0 so the entrypoint can hand the volume to uid 1000, "
                    + "or change the volume's ownership.");
        }
    }

    /**
     * Refuses to start when proxy trust is on and no proxy has been named.
     *
     * <p><strong>Why this exists when {@code ProxyProperties.validate()} already refuses the same
     * state.</strong> That guard fires, but only once the shipped default has been removed — and
     * while a default was shipped it was never empty, so the guard was unreachable in the one
     * deployment shape that matters. {@code application-prod.yml} listed loopback plus RFC 1918
     * plus RFC 4193 plus link-local, and a Render or Railway edge terminates at a public address
     * that matches none of it, so the list was satisfied by a value nobody had looked at. Every
     * credential request then arrived from an unsanctioned peer and was answered
     * {@code 503 CLIENT_IDENTITY_UNRESOLVED}, on all four credential endpoints, indefinitely.
     * {@code /actuator} is exempt from the limiter, so the deploy-time health gate passed and the
     * platform was published serving 100% errors on the primary user journey. Nothing in the log
     * said so after the first eight distinct peers.
     *
     * <p><strong>Can this class tell an explicit setting from the shipped default? No.</strong> It
     * runs after binding, and by then both cases are the same resolved value — there is no
     * provenance left to inspect, and {@code Environment} offers no way to ask which source or
     * which placeholder produced a value. Any check written as "was this set explicitly" would be
     * guessing at a fact the property system has already discarded. So the honest alternative is
     * used instead: <em>the default is removed</em>, so there is nothing for an unset value to
     * resolve to, and "unset" and "explicitly set to nothing" both become empty and both fail. The
     * operator cannot distinguish them either, which is correct — for this property there is no
     * deployment where an empty list is right while trust is on.
     *
     * <p>The consequence is therefore named rather than the cause alone: a missing list is not a
     * configuration warning, it is a total, silent outage of signup, login, password reset and
     * admin bootstrap, and the operator should see that on the deploy screen rather than
     * discover it from their own support tickets.
     */
    private void checkTrustedProxies(List<String> violations) {
        if (!environment.getProperty(TRUST_FORWARDED_HEADERS_PROPERTY, Boolean.class, Boolean.FALSE)) {
            return;
        }
        String configured = trimmed(TRUSTED_PROXIES_PROPERTY);
        if (configured != null) {
            return;
        }
        violations.add(TRUSTED_PROXIES_PROPERTY + " is empty while TRUST_FORWARDED_HEADERS is on, so "
                + "no socket peer is trusted and every request that arrives is REFUSED with 503 "
                + "CLIENT_IDENTITY_UNRESOLVED on POST /api/v1/auth/login, /signup, /register, "
                + "/forgot-password and /api/v1/admins/signup — nobody can sign in or create an "
                + "account. There is no shipped default for this list: the RFC 1918 ranges this "
                + "repository used to ship do not contain the address a Render, Railway or Fly.io "
                + "edge connects from, so a default that looked configured was silently refusing "
                + "every request. Set TRUSTED_PROXIES to the address or narrow CIDR of this "
                + "deployment's actual proxy, e.g. TRUSTED_PROXIES=10.0.0.0/8,172.17.0.1/32. To "
                + "find it, start once with TRUST_FORWARDED_HEADERS=false, read the socket peer in "
                + "the log, then set the list and turn trust back on. Do NOT leave "
                + "TRUST_FORWARDED_HEADERS off as the fix: that makes every caller share one "
                + "rate-limit bucket per rule, which is the outage this check exists to prevent, "
                + "and it names none of the peers.");
    }

    private boolean isPlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        if (KNOWN_PLACEHOLDER_VALUES.contains(normalized)) {
            return true;
        }
        return KNOWN_PLACEHOLDER_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    private String trimmed(String key) {
        String value = environment.getProperty(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
