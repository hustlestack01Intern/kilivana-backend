package com.kilivana.config;

import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Feeds the platform-injected {@code REDIS_URL} into {@code spring.data.redis.url}, which
 * is the property Spring Boot parses into host, port, credentials and database.
 *
 * <p>This exists rather than a {@code ${REDIS_URL:}} placeholder in application.yml because
 * a placeholder can only ever produce a string, never "absent". Spring Boot's
 * {@code parseUrl} accepts the {@code redis} and {@code rediss} schemes and rejects
 * everything else, including the empty string, so binding the property unconditionally
 * aborts startup with {@code Invalid Redis URL ''} on every platform that sets
 * {@code REDIS_HOST} / {@code REDIS_PORT} instead — docker compose, Render's Blueprint, and
 * every local run.
 *
 * <p>Registered as a {@link BeanFactoryPostProcessor} for the same reason as
 * {@link JdbcUrlConfiguration}: a Spring Boot fat jar keeps {@code META-INF} entries out of
 * the application classpath, so an {@code EnvironmentPostProcessor} declared in
 * {@code META-INF/spring/...imports} is silently never loaded from the packaged jar.
 */
@Configuration(proxyBeanMethods = false)
public class RedisUrlConfiguration {

    static final String PROPERTY_SOURCE_NAME = "kilivana-redis-url";
    static final String URL_VARIABLE = "REDIS_URL";
    static final String URL_PROPERTY = "spring.data.redis.url";

    @Bean
    static BeanFactoryPostProcessor redisUrlPostProcessor(ConfigurableEnvironment environment) {
        return beanFactory -> normalize(environment);
    }

    static void normalize(ConfigurableEnvironment environment) {
        String configured = environment.getProperty(URL_VARIABLE);
        if (!StringUtils.hasText(configured)) {
            // Nothing to do, and in particular nothing to override: leaving
            // spring.data.redis.url unbound is what keeps the host/port pair authoritative.
            return;
        }
        String url = configured.trim();
        if (!url.toLowerCase(Locale.ROOT).startsWith("redis://")
                && !url.toLowerCase(Locale.ROOT).startsWith("rediss://")) {
            // Never the value: REDIS_URL is redis://user:password@host, and this exception
            // reaches the log through Spring's startup failure analyzer.
            throw new IllegalArgumentException(
                    URL_VARIABLE + " must be a redis:// or rediss:// URI. Check the platform's "
                            + "database URL setting and that the credentials were not redacted by a typo.");
        }
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(URL_PROPERTY, url)));
    }
}
