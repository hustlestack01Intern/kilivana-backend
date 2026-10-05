package com.kilivana.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class DeploymentEnvironmentValidatorTest {

    private static final String LONG_ENOUGH = "k1l1v4n4-8Zq2!Lm7#pR4vTx9dYc3Nb6Fg1Hj";
    private static final String BOOTSTRAP_KEY_LONG_ENOUGH = "9Qf4!Zx2Nv7Bw1Kp6Lm3Rt8Yd5Hc0Js2Ga";

    @Test
    @DisplayName("every violation is reported in one failure, each naming its environment variable")
    void reportsEveryViolationAtOnce() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("kilivana.security.jwt.secret", "");
        environment.setProperty("kilivana.security.rate-limit.backend", "memory");
        environment.setProperty("kilivana.media.root-dir", "/nonexistent-volume/kilivana/nested/uploads");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("ADMIN_BOOTSTRAP_KEY")
                .hasMessageContaining("RATE_LIMIT_BACKEND=redis")
                .hasMessageContaining("MEDIA_ROOT");
    }

    @Test
    @DisplayName("the dead general rate-limit variable refuses to start, naming what to remove")
    void refusesTheDeadGeneralRateLimitVariable() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("RATE_LIMIT_REQUESTS_PER_MINUTE", "120");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RATE_LIMIT_REQUESTS_PER_MINUTE")
                .hasMessageContaining("not implemented")
                .hasMessageContaining("does nothing")
                .hasMessageContaining("remove it from the service variables");
    }

    @Test
    @DisplayName("an empty general rate-limit variable is still a variable the operator set")
    void refusesAnEmptyGeneralRateLimitVariable() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("RATE_LIMIT_REQUESTS_PER_MINUTE", "");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("RATE_LIMIT_REQUESTS_PER_MINUTE");
    }

    @Test
    @DisplayName("a valid prod configuration does not trip the general rate-limit check")
    void aValidProdConfigurationDoesNotTripTheGeneralRateLimitCheck() {
        MockEnvironment environment = validEnvironment();

        assertThat(new DeploymentEnvironmentValidator(environment).violations()).isEmpty();
    }

    @Test
    @DisplayName("the memory rate-limit backend names the per-instance limitation, not just the value")
    void explainsWhyTheMemoryBackendIsRefused() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.rate-limit.backend", "memory");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("per application instance")
                .hasMessageContaining("replica count");
    }

    @Test
    @DisplayName("a repository-known JWT secret is refused however long it is")
    void refusesKnownPlaceholders() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty(
                "kilivana.security.jwt.secret",
                "local-development-secret-at-least-32-characters-long");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("placeholder")
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("a known admin bootstrap key is refused")
    void refusesKnownAdminBootstrapKey() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty(
                "kilivana.security.admin.bootstrap-key",
                "local-development-admin-bootstrap-key");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("a short secret is refused with its length and never with its value")
    void reportsSecretLengthWithoutTheValue() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.jwt.secret", "short-secret-value-here");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("23 characters")
                .hasMessageNotContaining("short-secret-value-here");
    }

    @Test
    @DisplayName("simulated password-reset delivery is refused as defence in depth")
    void refusesSimulatedPasswordResetDelivery() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.password-reset.simulate-delivery", "true");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("simulate-delivery");
    }

    @Test
    @DisplayName("a valid prod configuration starts, and the media root is created when it is missing")
    void acceptsAValidProdConfiguration(@TempDir Path tempDir) {
        Path mediaRoot = tempDir.resolve("fresh-uploads");
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.media.root-dir", mediaRoot.toString());

        assertThatCode(() -> new DeploymentEnvironmentValidator(environment).validate())
                .doesNotThrowAnyException();
        assertThat(Files.isDirectory(mediaRoot)).isTrue();
    }

    @Test
    @DisplayName("the validator does not activate when the prod profile is absent")
    void doesNotApplyOffProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        // Nothing is configured at all: without the profile gate this would throw.
        DeploymentEnvironmentValidator validator = new DeploymentEnvironmentValidator(environment);

        assertThat(validator.applies()).isFalse();
        assertThat(validator.violations()).isNotEmpty();

        // And the context that wires it stays up, so a local run and the test suite are
        // unaffected by a preflight that only ever meant for a deployment.
        contextRunner()
                .withPropertyValues("kilivana.security.jwt.secret=")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("the profile gate matches prod exactly, and prod counts among several profiles")
    void profileGateIsExact() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("render", "prod", "metrics");
        assertThat(new DeploymentEnvironmentValidator(environment).applies()).isTrue();

        environment.setActiveProfiles("production");
        assertThat(new DeploymentEnvironmentValidator(environment).applies()).isFalse();
    }

    @Test
    @DisplayName("a generated base64 secret containing the word 'secret' by chance is accepted")
    void acceptsAGeneratedSecretThatMerelyContainsAPlaceholderWord(@TempDir Path tempDir) {
        // "secret" occurs by chance in roughly one base64 secret in twelve. Matching it as a
        // substring made startup refuse a perfectly good `openssl rand -base64 32` output about
        // 9% of the time, which is the worst possible failure for a preflight check.
        MockEnvironment environment = validEnvironment();
        environment.setProperty(
                "kilivana.security.jwt.secret",
                "kR3secretXq9wZm2Vb7Lp4Ht1Ns8Dc5Gy0Ju6Ae3Fi");
        environment.setProperty("kilivana.media.root-dir", tempDir.toString());

        assertThatCode(() -> new DeploymentEnvironmentValidator(environment).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty secret is treated as absent rather than as a zero-length secret")
    void emptySecretIsTreatedAsMissing(@TempDir Path tempDir) {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.jwt.secret", "   ");
        environment.setProperty("kilivana.media.root-dir", tempDir.toString());

        assertThat(new DeploymentEnvironmentValidator(environment).violations())
                .anyMatch(violation -> violation.contains("JWT_SECRET"));
    }

    @Test
    @DisplayName("an unwritable media root is reported as a mount ownership problem")
    void reportsUnwritableMediaRoot(@TempDir Path tempDir) throws IOException {
        Path mediaRoot = Files.createDirectory(tempDir.resolve("uploads"));
        mediaRoot.toFile().setWritable(false, false);
        // Root ignores the write bit entirely, so the branch below is only meaningful
        // when the suite runs as an unprivileged user.
        Assumptions.assumeFalse(Files.isWritable(mediaRoot));
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.media.root-dir", mediaRoot.toString());

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("not writable")
                .hasMessageContaining("RAILWAY_RUN_UID=0");
    }

    @Test
    @DisplayName("the context refuses to start under prod and reports every problem together")
    void contextFailsUnderProd() {
        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "kilivana.security.jwt.secret=local-development-secret-at-least-32-characters-long",
                        "kilivana.security.rate-limit.backend=memory",
                        "kilivana.media.root-dir=/nonexistent-volume/kilivana/uploads")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("JWT_SECRET")
                            .hasMessageContaining("RATE_LIMIT_BACKEND=redis")
                            .hasMessageContaining("MEDIA_ROOT");
                });
    }

    @Test
    @DisplayName("the same context starts cleanly when prod is supplied correctly")
    void contextStartsWithValidProdConfiguration(@TempDir Path tempDir) {
        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "kilivana.security.jwt.secret=" + LONG_ENOUGH,
                        "kilivana.security.admin.bootstrap-key=" + BOOTSTRAP_KEY_LONG_ENOUGH,
                        "kilivana.security.rate-limit.backend=redis",
                        "kilivana.media.root-dir=" + tempDir,
                        "REDIS_HOST=redis.internal",
                        // The preflight requires SMTP because password reset is the only
                        // account-recovery path. A prod context without it must not start, and
                        // that is what the two tests below pin.
                        "spring.mail.host=smtp.internal",
                        "spring.mail.username=kilivana",
                        "spring.mail.password=relay-secret")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "your-super-secret-jwt-key-min-32-chars-long-for-hs256",
            "test-secret-key-minimum-32-characters-long-for-hs256",
            "change-me-to-a-long-random-secret-min-32-bytes",
            "change-me-to-a-long-random-secret",
            "dev-only-secret-change-me-4f3f2d1c",
            "local-development-secret-at-least-32-characters-long",
            "test-secret-key-that-is-at-least-thirty-two-bytes-long"
    })
    @DisplayName("every JWT secret this repository has ever committed is refused")
    void refusesEveryHistoricalJwtSecret(String historicalSecret) {
        // Enumerated from `git log -p --all` and `git grep` across every revision. A value that
        // only ever ran in a pipeline or a laptop is still published, and every one of these
        // boots clean on prod today. "change-me-to-a-long-random-secret" is in the list
        // separately from its longer sibling because it is 33 characters and clears the length
        // floor by itself: matched only as a prefix of the longer value, it is not caught.
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.jwt.secret", historicalSecret);

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("placeholder")
                .hasMessageContaining("JWT_SECRET");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "change-me-in-production",
            "test-bootstrap-key",
            "kilivana-dev-bootstrap-key",
            "some-long-bootstrap-key",
            "local-development-admin-bootstrap-key"
    })
    @DisplayName("every admin bootstrap key this repository has ever committed is refused")
    void refusesEveryHistoricalAdminBootstrapKey(String historicalKey) {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.admin.bootstrap-key", historicalKey);

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("placeholder")
                .hasMessageContaining("ADMIN_BOOTSTRAP_KEY");
    }

    @Test
    @DisplayName("a short admin bootstrap key is refused with its length, like the JWT secret")
    void refusesAShortAdminBootstrapKey() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.admin.bootstrap-key", "7Yq2!Lm9pR4vTx");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("14 characters")
                .hasMessageContaining("ADMIN_BOOTSTRAP_KEY")
                // The length is safe to print. The value is not.
                .hasMessageNotContaining("7Yq2!Lm9pR4vTx");
    }

    @Test
    @DisplayName("a bootstrap key that is only a known placeholder is reported as that, not as too short")
    void aKnownShortBootstrapKeyIsReportedAsAPlaceholder() {
        // "test-bootstrap-key" is 16 characters, so a length-first check would report the milder
        // problem for the input that is actually the dangerous one.
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.admin.bootstrap-key", "test-bootstrap-key");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("placeholder")
                .hasMessageNotContaining("16 characters");
    }

    @Test
    @DisplayName("the remediation for simulated password reset names the variable that binds")
    void thePasswordResetRemediationNamesTheBindingForm() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("kilivana.security.password-reset.simulate-delivery", "true");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                // application.yml declares no ${PASSWORD_RESET_SIMULATE_DELIVERY:} placeholder,
                // so that bare name binds to nothing and following the message changed nothing.
                .hasMessageContaining("KILIVANA_SECURITY_PASSWORD_RESET_SIMULATE_DELIVERY=false")
                .hasMessageContaining("PASSWORD_RESET_SIMULATE_DELIVERY is wired to nothing");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "RATE_LIMIT_REQUESTS_PER_MINUTE",
            "KILIVANA_SECURITY_RATE_LIMIT_REQUESTS_PER_MINUTE"
    })
    @DisplayName("both spellings of the dead general rate-limit variable refuse startup")
    void refusesBothSpellingsOfTheDeadGeneralRateLimitVariable(String variable) {
        // The second is the relaxed-binding form of kilivana.security.rate-limit.requests-per-minute,
        // which no record component claims. Refusing only the first left an equivalent bypass.
        MockEnvironment environment = validEnvironment();
        environment.setProperty(variable, "120");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining(variable)
                .hasMessageContaining("not implemented")
                .hasMessageContaining("RATE_LIMIT_REQUESTS_PER_MINUTE")
                .hasMessageContaining("KILIVANA_SECURITY_RATE_LIMIT_REQUESTS_PER_MINUTE");
    }

    @Test
    @DisplayName("an empty value is still a variable the operator set")
    void refusesTheRelaxedBindingFormEvenWhenEmpty() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("KILIVANA_SECURITY_RATE_LIMIT_REQUESTS_PER_MINUTE", "");

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("KILIVANA_SECURITY_RATE_LIMIT_REQUESTS_PER_MINUTE");
    }

    @Test
    @DisplayName("no Redis configuration at all refuses startup, naming both variables")
    void refusesWhenNeitherRedisVariableIsPresent() {
        MockEnvironment environment = validEnvironmentWithoutRedis();

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("REDIS_URL")
                .hasMessageContaining("REDIS_HOST")
                // A limiter that cannot reach Redis answers 503 on every credential endpoint.
                .hasMessageContaining("503");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"localhost", "127.0.0.1", "127.0.0.53", "::1", "[::1]"})
    @DisplayName("a loopback Redis host with no REDIS_URL refuses startup")
    void refusesALoopbackOnlyRedisHost(String loopbackHost) {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("REDIS_HOST", loopbackHost);

        assertThatThrownBy(() -> new DeploymentEnvironmentValidator(environment).validate())
                .hasMessageContaining("loopback")
                .hasMessageContaining("REDIS_URL");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"redis.internal", "10.0.0.7", "redis.railway.internal"})
    @DisplayName("a non-loopback REDIS_HOST is accepted")
    void acceptsANonLoopbackRedisHost(String host) {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("REDIS_HOST", host);

        assertThat(new DeploymentEnvironmentValidator(environment).violations()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"redis://:pw@redis.internal:6379", "rediss://cache.internal:6380"})
    @DisplayName("a REDIS_URL is accepted even when REDIS_HOST is absent")
    void acceptsARedisUrlWithoutARedisHost(String url) {
        MockEnvironment environment = validEnvironmentWithoutRedis();
        environment.setProperty("REDIS_URL", url);

        assertThat(new DeploymentEnvironmentValidator(environment).violations()).isEmpty();
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DeploymentEnvironmentValidatorConfiguration.class);
    }

    private static MockEnvironment validEnvironment() {
        MockEnvironment environment = validEnvironmentWithoutRedis();
        environment.setProperty("REDIS_HOST", "redis.internal");
        return environment;
    }

    /**
     * Everything a sound prod deployment has, except any Redis configuration. Split out rather
     * than unset afterwards because {@code MockEnvironment} funnels every {@code setProperty}
     * into a single source and refuses a null value, so a property cannot be taken back out.
     */
    private static MockEnvironment validEnvironmentWithoutRedis() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("kilivana.security.jwt.secret", LONG_ENOUGH);
        environment.setProperty("kilivana.security.admin.bootstrap-key", BOOTSTRAP_KEY_LONG_ENOUGH);
        environment.setProperty("kilivana.security.rate-limit.backend", "redis");
        environment.setProperty("kilivana.media.root-dir", System.getProperty("java.io.tmpdir"));
        // Password reset is the only account-recovery path in the platform, so a deployment
        // without a mail host is not a valid deployment. Every "valid prod environment" in
        // this file therefore configures SMTP; the tests that assert the opposite use an
        // environment built from this one with the mail properties deliberately absent.
        environment.setProperty("spring.mail.host", "smtp.internal");
        environment.setProperty("spring.mail.username", "kilivana");
        environment.setProperty("spring.mail.password", "relay-secret");
        return environment;
    }

    /**
     * A prod environment with everything correct except SMTP, used to assert that the mail
     * check fires. {@link MockEnvironment} cannot unset a property, so this rebuilds the
     * environment from scratch rather than deleting from the valid one.
     */
    private static MockEnvironment validProdEnvironmentWithoutMail() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("kilivana.security.jwt.secret", LONG_ENOUGH);
        environment.setProperty("kilivana.security.admin.bootstrap-key", BOOTSTRAP_KEY_LONG_ENOUGH);
        environment.setProperty("kilivana.security.rate-limit.backend", "redis");
        environment.setProperty("kilivana.media.root-dir", System.getProperty("java.io.tmpdir"));
        environment.setProperty("REDIS_HOST", "redis.internal");
        return environment;
    }

    @Test
    @DisplayName("a prod deployment with no mail host is refused: password reset is the only recovery path")
    void refusesAProdDeploymentWithoutMail() {
        List<String> violations = new DeploymentEnvironmentValidator(validProdEnvironmentWithoutMail())
                .violations();

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation).contains("spring.mail.host");
            assertThat(violation).contains("MAIL_HOST");
        });
    }

    @Test
    @DisplayName("a mail host without credentials is called out, because relays reject anonymous sends")
    void refusesAProdDeploymentWithMailButNoCredentials() {
        MockEnvironment environment = validProdEnvironmentWithoutMail();
        environment.setProperty("spring.mail.host", "smtp.internal");

        List<String> violations = new DeploymentEnvironmentValidator(environment).violations();

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation).contains("spring.mail.username"));
    }

    @Test
    @DisplayName("a profile that looks production but is not 'prod' is refused by name")
    void refusesAProductionProfileAlias() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles("production");

        List<String> violations = new DeploymentEnvironmentValidator(environment).violations();

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation).contains("SPRING_PROFILES_ACTIVE");
            assertThat(violation).contains("prod");
        });
    }

    @Test
    @DisplayName("the exact 'prod' profile is not treated as an alias of itself")
    void acceptsTheRealProdProfile() {
        assertThat(new DeploymentEnvironmentValidator(validEnvironment()).violations()).isEmpty();
    }
}
