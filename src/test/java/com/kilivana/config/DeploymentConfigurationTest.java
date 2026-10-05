package com.kilivana.config;

import static org.assertj.core.api.Assertions.assertThat;
import static com.kilivana.config.JdbcUrlConfiguration.PASSWORD_PROPERTY;
import static com.kilivana.config.JdbcUrlConfiguration.PROPERTY_SOURCE_NAME;
import static com.kilivana.config.JdbcUrlConfiguration.USERNAME_PROPERTY;
import static com.kilivana.config.JdbcUrlConfiguration.URL_PROPERTY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kilivana.security.config.RateLimitProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class DeploymentConfigurationTest {

    @ParameterizedTest
    @CsvSource({
        "postgres://user:pw@db.internal:5432/kilivana, jdbc:postgresql://db.internal:5432/kilivana",
        "postgresql://user:pw@db.internal:5432/kilivana, jdbc:postgresql://db.internal:5432/kilivana",
        "postgres://db.internal/kilivana, jdbc:postgresql://db.internal/kilivana",
        "postgres://db.internal:5432, jdbc:postgresql://db.internal:5432/",
        "postgres://user:pw@db.internal/kilivana?sslmode=require, jdbc:postgresql://db.internal/kilivana?sslmode=require"
    })
    @DisplayName("provider connection URIs are rewritten into JDBC URLs")
    void convertsProviderUris(String configured, String expected) {
        assertThat(JdbcUrls.toJdbcUrl(configured)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://localhost:5432/kilivana",
        "jdbc:postgresql://user:pw@db:5432/kilivana?sslmode=require",
        "jdbc:h2:mem:test"
    })
    @DisplayName("URLs that are already JDBC are passed through untouched")
    void keepsJdbcUrls(String jdbcUrl) {
        assertThat(JdbcUrls.toJdbcUrl(jdbcUrl)).isEqualTo(jdbcUrl);
    }

    @Test
    @DisplayName("unrecognised schemes are left alone so the driver reports the real cause")
    void leavesUnknownSchemesAlone() {
        assertThat(JdbcUrls.toJdbcUrl("mysql://localhost:3306/kilivana"))
                .isEqualTo("mysql://localhost:3306/kilivana");
        assertThat(JdbcUrls.toJdbcUrl(null)).isNull();
    }

    @Test
    @DisplayName("credentials embedded in a provider URI are recovered and percent-decoded")
    void recoversCredentialsFromProviderUri() {
        JdbcUrls.Parsed parsed = JdbcUrls.parse("postgres://kilivana:p%40ss%3Aword@dpg-abc/kilivana");

        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc/kilivana");
        assertThat(parsed.username()).isEqualTo("kilivana");
        assertThat(parsed.password()).isEqualTo("p@ss:word");
    }

    @Test
    @DisplayName("a JDBC URL contributes no credentials so DB_USERNAME and DB_PASSWORD still apply")
    void jdbcUrlContributesNoCredentials() {
        assertThat(JdbcUrls.parse("jdbc:postgresql://db:5432/kilivana"))
                .isEqualTo(new JdbcUrls.Parsed("jdbc:postgresql://db:5432/kilivana", null, null));
    }

    @Test
    @DisplayName("a provider URI rewrites the url and supplies the credentials the driver needs")
    void providerUriPopulatesUrlAndCredentials() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                URL_PROPERTY, "postgres://app:s3cret@db.internal:5432/kilivana")));

        JdbcUrlConfiguration.normalize(environment);

        assertThat(environment.getProperty(URL_PROPERTY))
                .isEqualTo("jdbc:postgresql://db.internal:5432/kilivana");
        assertThat(environment.getProperty(USERNAME_PROPERTY)).isEqualTo("app");
        assertThat(environment.getProperty(PASSWORD_PROPERTY)).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("explicitly configured credentials are never overwritten by the URL")
    void explicitCredentialsWin() throws IOException {
        ConfigurableEnvironment environment = environmentWith(
                "DB_URL", "postgres://url-user:url-pass@db.internal/kilivana",
                "DB_USERNAME", "env-user",
                "DB_PASSWORD", "env-pass");

        JdbcUrlConfiguration.normalize(environment);

        assertThat(environment.getProperty(URL_PROPERTY))
                .isEqualTo("jdbc:postgresql://db.internal/kilivana");
        assertThat(environment.getProperty(USERNAME_PROPERTY)).isEqualTo("env-user");
        assertThat(environment.getProperty(PASSWORD_PROPERTY)).isEqualTo("env-pass");
    }

    @Test
    @DisplayName("a JDBC url leaves the environment untouched")
    void jdbcUrlLeavesEnvironmentUntouched() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                URL_PROPERTY, "jdbc:postgresql://db:5432/kilivana")));

        JdbcUrlConfiguration.normalize(environment);

        assertThat(environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(environment.getProperty(USERNAME_PROPERTY)).isNull();
    }

    @Test
    @DisplayName("the credential default in application.yml does not mask the URL credentials")
    void credentialDefaultsDoNotMaskUrlCredentials() throws IOException {
        ConfigurableEnvironment environment = environmentWith("spring.datasource.url",
                "postgres://app:s3cret@db.internal:5432/kilivana");

        JdbcUrlConfiguration.normalize(environment);

        // application.yml declares ${DB_USERNAME:postgres}. Resolving the property first
        // would report "postgres" and discard the credentials, so the normalizer would
        // never fire on Render, which sets DB_URL and nothing else.
        assertThat(environment.getProperty(USERNAME_PROPERTY)).isEqualTo("app");
        assertThat(environment.getProperty(PASSWORD_PROPERTY)).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("a deployed application.yml turns a Railway DATABASE_URL into a usable datasource")
    void railwayDatabaseUrlIsUsable() throws IOException {
        ConfigurableEnvironment environment = environmentWith("DATABASE_URL",
                "postgres://kilivana:p%40ss@dpg-abc.railway.internal:5432/kilivana");

        JdbcUrlConfiguration.normalize(environment);

        assertThat(environment.getProperty(URL_PROPERTY))
                .isEqualTo("jdbc:postgresql://dpg-abc.railway.internal:5432/kilivana");
        assertThat(environment.getProperty(USERNAME_PROPERTY)).isEqualTo("kilivana");
        assertThat(environment.getProperty(PASSWORD_PROPERTY)).isEqualTo("p@ss");
    }

    @Test
    @DisplayName("a platform REDIS_URL reaches Spring Boot, which parses the URI itself")
    void platformRedisUrlIsBound() throws IOException {
        ConfigurableEnvironment environment = environmentWith("REDIS_URL", "redis://redis.internal:6380");

        RedisUrlConfiguration.normalize(environment);

        assertThat(environment.getProperty("spring.data.redis.url"))
                .isEqualTo("redis://redis.internal:6380");
    }

    @Test
    @DisplayName("without a REDIS_URL the url property stays absent so the host/port pair wins")
    void noRedisUrlLeavesTheUrlPropertyUnset() throws IOException {
        ConfigurableEnvironment environment = environmentWith();

        RedisUrlConfiguration.normalize(environment);

        // This is the regression: binding spring.data.redis.url to a ${REDIS_URL:}
        // placeholder leaves an empty string, and Spring Boot's parseUrl accepts only the
        // redis and rediss schemes, so it aborted startup with "Invalid Redis URL ''" on
        // every platform that sets REDIS_HOST instead. An absent property parses fine.
        assertThat(environment.getProperty("spring.data.redis.url")).isNull();
        assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("localhost");
        assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("6379");
    }

    @Test
    @DisplayName("an empty REDIS_URL is treated as unset rather than passed to the parser")
    void emptyRedisUrlIsTreatedAsUnset() throws IOException {
        ConfigurableEnvironment environment = environmentWith("REDIS_URL", "   ");

        RedisUrlConfiguration.normalize(environment);

        assertThat(environment.getProperty("spring.data.redis.url")).isNull();
    }

    @Test
    @DisplayName("a malformed REDIS_URL fails at startup naming the accepted schemes, never the value")
    void rejectsNonRedisUriWithActionableMessage() throws IOException {
        ConfigurableEnvironment environment = environmentWith("REDIS_URL", "http://redis-user:s3cret@redis.internal:6379");

        assertThatThrownBy(() -> RedisUrlConfiguration.normalize(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a redis:// or rediss:// URI")
                // The value is redis://user:password@host and this exception is rendered by
                // Spring's failure analyzer, so interpolating it wrote the password to the log.
                .hasMessageNotContaining("s3cret")
                .hasMessageNotContaining("redis-user")
                .hasMessageNotContaining("redis.internal");
    }

    @Test
    @DisplayName("an explicit REDIS_HOST is what docker compose and Render's Blueprint set")
    void explicitRedisHostApplies() throws IOException {
        RedisProperties properties = redisPropertiesOf(environmentWith("REDIS_HOST", "redis"));

        assertThat(properties.getHost()).isEqualTo("redis");
    }

    @Test
    @DisplayName("a TLS Redis URI is accepted, since providers use rediss for managed caches")
    void acceptsTlsRedisUri() throws IOException {
        ConfigurableEnvironment environment = environmentWith("REDIS_URL", "rediss://cache.internal:6380");

        RedisUrlConfiguration.normalize(environment);

        assertThat(environment.getProperty("spring.data.redis.url"))
                .isEqualTo("rediss://cache.internal:6380");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "inmemory", "in-memory", "local", "  MEMORY  "})
    @DisplayName("every documented alias of the in-process limiter resolves to the memory backend")
    void acceptsInMemoryAliases(String configured) {
        RateLimitProperties properties = new RateLimitProperties(true, configured, 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 10_000);
        assertThat(properties.backend()).isEqualTo("memory");
    }

    @Test
    @DisplayName("a misspelt backend fails at binding time naming the accepted values")
    void rejectsUnknownBackendWithActionableMessage() {
        assertThatThrownBy(() -> new RateLimitProperties(true, "inmemoryy", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 'redis' or 'memory'")
                .hasMessageContaining("inmemoryy");
    }

    @ParameterizedTest
    @CsvSource({
        "postgres://u:p@[::1]:5432/db, jdbc:postgresql://[::1]:5432/db",
        "postgres://u:p@[2001:db8::1]:5432/db, jdbc:postgresql://[2001:db8::1]:5432/db",
        "postgres://u:p@[::1]/db, jdbc:postgresql://[::1]/db",
        "postgres://u:p@db.internal:5432/kilivana, jdbc:postgresql://db.internal:5432/kilivana",
        "postgres://u:p@db.internal/kilivana?sslmode=require, jdbc:postgresql://db.internal/kilivana?sslmode=require"
    })
    @DisplayName("an IPv6 host is bracketed exactly once, not twice")
    void bracketsIpv6HostsExactlyOnce(String configured, String expected) {
        // URI.getHost() returns "[::1]" with the brackets still attached, so wrapping it
        // again produced "[[::1]]" and the driver looked for a host that cannot exist.
        assertThat(JdbcUrls.toJdbcUrl(configured)).isEqualTo(expected);
        assertThat(JdbcUrls.toJdbcUrl(configured)).doesNotContain("[[");
    }

    @Test
    @DisplayName("a bracketed IPv4 literal is repaired into a usable JDBC URL")
    void repairsABracketedIpv4Literal() {
        // URI.getHost() returns null for "[192.168.1.10]" because the brackets claim an IPv6
        // literal that is not one. Passing the value through untouched made the driver abort
        // with "URL must start with 'jdbc'" and dropped the credentials on the way.
        String repaired = "postgres://u:p@[192.168.1.10]:5432/db";

        assertThat(JdbcUrls.toJdbcUrl(repaired)).isEqualTo("jdbc:postgresql://192.168.1.10:5432/db");
        assertThat(JdbcUrls.parse(repaired))
                .isEqualTo(new JdbcUrls.Parsed("jdbc:postgresql://192.168.1.10:5432/db", "u", "p"));
    }

    @ParameterizedTest
    @CsvSource({
        "postgres://u:p@[192.168.1.10]/db,        jdbc:postgresql://192.168.1.10/db",
        "postgres://u@[10.0.0.7]:5432/db,        jdbc:postgresql://10.0.0.7:5432/db",
        "postgres://[172.16.4.4]/db,             jdbc:postgresql://172.16.4.4/db",
        "postgres://u:p@[192.168.1.10]:5432/db?sslmode=require, jdbc:postgresql://192.168.1.10:5432/db?sslmode=require"
    })
    @DisplayName("a bracketed IPv4 literal is repaired with its port, path and query intact")
    void repairsABracketedIpv4LiteralInEveryPosition(String configured, String expected) {
        assertThat(JdbcUrls.toJdbcUrl(configured)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a bracketed IPv6 literal is never unwrapped into something unparseable")
    void neverUnwrapsABracketedIpv6Literal() {
        // The repair is deliberately restricted to colon-free bracketed text. Widening it to
        // IPv6 would turn "postgres://u:p@[::1]:5432/db" into "::1:5432/db", which does not
        // parse, so the value would come back unconverted and the working case would break.
        assertThat(JdbcUrls.toJdbcUrl("postgres://u:p@[::1]:5432/db"))
                .isEqualTo("jdbc:postgresql://[::1]:5432/db");
    }

    @Test
    @DisplayName("an unparseable provider URI is reported as a format problem, without the credentials")
    void anUnparseableProviderUriNamesTheFormatProblemNotTheCredentials() {
        // Previously the value was passed through untouched with null credentials. The driver
        // then aborted with "URL must start with 'jdbc'", spring.datasource.username kept its
        // ${DB_USERNAME:postgres} default, and the operator was reading a wrong-credential
        // error caused by a malformed host.
        String malformed = "postgres://kilivana:s3cr3t-pw@ho st.internal:5432/kilivana";

        assertThatThrownBy(() -> JdbcUrls.parse(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed")
                .hasMessageContaining("whitespace")
                .hasMessageContaining("postgres://USER:PASSWORD@HOST:5432/DATABASE")
                // The exception is rendered by Spring's failure analyzer and written to the
                // deploy log, so the value must not be in it.
                .hasMessageNotContaining("s3cr3t-pw")
                .hasMessageNotContaining("kilivana:")
                .hasMessageNotContaining("st.internal");
    }

    @Test
    @DisplayName("a provider URI with no host is named as such, not as a bad credential")
    void anAuthorityWithNoHostIsNamedAsSuch() {
        assertThatThrownBy(() -> JdbcUrls.parse("postgres://kilivana:s3cr3t-pw@/kilivana"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries no host")
                .hasMessageNotContaining("s3cr3t-pw");
    }

    @Test
    @DisplayName("a genuinely unrecognised scheme is still passed through, not refused")
    void anUnrecognisedSchemeIsStillPassedThrough() {
        // The guard is deliberately narrow: refusing every value that fails to parse would
        // break a platform that injects something this class has never heard of, and the
        // driver is better placed to report an unrecognised scheme than this class is.
        assertThat(JdbcUrls.toJdbcUrl("mysql://user:pw@db.internal:3306/kilivana"))
                .isEqualTo("mysql://user:pw@db.internal:3306/kilivana");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "postgres://u:pw%4@db.internal:5432/db",
            "postgres://u:pw%zz@db.internal:5432/db",
            "postgres://u:%@db.internal:5432/db"
    })
    @DisplayName("a truncated or non-hex percent escape does not abort startup")
    void survivesAMalformedPercentEscape(String configured) {
        // URLDecoder throws IllegalArgumentException on these. That escaped the whole of
        // parse() and aborted the deployment over one character of a password.
        assertThat(JdbcUrls.toJdbcUrl(configured))
                .isEqualTo("jdbc:postgresql://db.internal:5432/db");
        assertThat(JdbcUrls.parse(configured).username()).isEqualTo("u");
        assertThat(JdbcUrls.parse(configured).password()).isNotNull();
    }

    @Test
    @DisplayName("the platform-injected PORT wins over SERVER_PORT, which wins over the default")
    void portPrecedence() throws IOException {
        assertThat(portWith("PORT", "9099", "SERVER_PORT", "8081")).isEqualTo("9099");
        assertThat(portWith("SERVER_PORT", "9099")).isEqualTo("9099");
        assertThat(portWith()).isEqualTo("8080");
    }

    @Test
    @DisplayName("the credential-abuse limits are the only rate limits that exist, and they stay deliberately low")
    void rateLimitDefaults() throws IOException {
        RateLimitProperties properties = boundRateLimitProperties(environmentWith());

        assertThat(properties.backend()).isEqualTo("redis");
        // The general limit is gone. These four are the whole enforcement surface.
        assertThat(properties.loginRequestsPerMinute()).isEqualTo(5);
        assertThat(properties.registrationRequestsPerMinute()).isEqualTo(5);
        assertThat(properties.forgotPasswordRequestsPerMinute()).isEqualTo(3);
        assertThat(properties.adminBootstrapRequestsPerMinute()).isEqualTo(3);
    }

    // thereIsNoGeneralRateLimitProperty used to live here, asserting by reflection that no
    // record component was named "requestsPerMinute" and that application.yml contained no such
    // key. It proved the shape of a class rather than the behaviour of the filter, and it
    // passed identically whether or not anything read the property — the same fallacy this
    // repository's decision record indicts. The invariant is now pinned where it can fail:
    // RateLimitSecurityTest.noPathWithoutASpecialisedRuleConsumesABucket drives the real
    // filter over six paths x four methods and asserts the limiter is never consulted, and
    // RateLimitSecurityTest.everyCredentialRuleStillLimits pins rule name, limit and window.

    private static RateLimitProperties boundRateLimitProperties(ConfigurableEnvironment environment) {
        return Binder.get(environment).bind("kilivana.security.rate-limit", RateLimitProperties.class).get();
    }

    /**
     * Resolves {@code server.port} against the real {@code application.yml}. The system
     * environment is detached first: the point of the test is the placeholder precedence in
     * the file, and a {@code PORT} already exported on the machine running the build would
     * silently answer all three assertions.
     */
    private static String portWith(String... nameThenValue) throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        if (nameThenValue.length > 0) {
            Map<String, Object> platform = new LinkedHashMap<>();
            for (int index = 0; index + 1 < nameThenValue.length; index += 2) {
                platform.put(nameThenValue[index], nameThenValue[index + 1]);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("platform", platform));
        }
        return environment.getProperty("server.port");
    }

    private static RedisProperties redisPropertiesOf(ConfigurableEnvironment environment) {
        return Binder.get(environment)
                .bind("spring.data.redis", RedisProperties.class)
                .orElseGet(RedisProperties::new);
    }

    /**
     * A property environment with the real {@code application.yml} behind the given
     * variables, so the tests exercise the placeholder defaults the application ships with
     * rather than a bare environment where every property is absent.
     */
    private static ConfigurableEnvironment environmentWith(String... nameThenValue) throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        if (nameThenValue.length > 0) {
            Map<String, Object> platform = new LinkedHashMap<>();
            for (int index = 0; index + 1 < nameThenValue.length; index += 2) {
                platform.put(nameThenValue[index], nameThenValue[index + 1]);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("platform", platform));
        }
        return environment;
    }
}
