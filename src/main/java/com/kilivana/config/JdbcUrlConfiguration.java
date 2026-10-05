package com.kilivana.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Makes {@code spring.datasource.*} accept the connection URIs that PaaS providers
 * supply, so one deployment configuration works on Render, Railway, Fly.io and Heroku
 * without rewriting the URL and credentials per platform.
 *
 * <p>Registered as a {@link BeanFactoryPostProcessor} rather than an
 * {@code EnvironmentPostProcessor} on purpose: a Spring Boot fat jar keeps {@code META-INF}
 * entries out of the application classpath, so an {@code EnvironmentPostProcessor}
 * declared in {@code META-INF/spring/...imports} is silently never loaded from the
 * packaged jar and the failure only shows up in production.
 */
@Configuration(proxyBeanMethods = false)
public class JdbcUrlConfiguration {

    static final String PROPERTY_SOURCE_NAME = "kilivana-jdbc-url-normalizer";
    static final String URL_PROPERTY = "spring.datasource.url";
    static final String USERNAME_PROPERTY = "spring.datasource.username";
    static final String PASSWORD_PROPERTY = "spring.datasource.password";

    /**
     * The variables application.yml binds the two credential properties to. They have to
     * be read directly rather than through {@code spring.datasource.username}, because
     * that property carries the default {@code postgres} and therefore always resolves to
     * a non-blank value. Testing the resolved property would make the normalizer believe
     * an operator had configured credentials on every platform that sets only DB_URL, and
     * it would discard the credentials the URL carries.
     */
    static final String USERNAME_VARIABLE = "DB_USERNAME";
    static final String PASSWORD_VARIABLE = "DB_PASSWORD";

    @Bean
    static BeanFactoryPostProcessor jdbcUrlNormalizerPostProcessor(ConfigurableEnvironment environment) {
        return beanFactory -> normalize(environment);
    }

    static void normalize(ConfigurableEnvironment environment) {
        String configured = environment.getProperty(URL_PROPERTY);
        if (!StringUtils.hasText(configured)) {
            return;
        }
        JdbcUrls.Parsed parsed = JdbcUrls.parse(configured);
        Map<String, Object> overrides = new LinkedHashMap<>();
        if (!parsed.jdbcUrl().equals(configured.trim())) {
            overrides.put(URL_PROPERTY, parsed.jdbcUrl());
        }
        // Explicit credentials always win: a platform that sets DB_USERNAME separately
        // is telling us something the URL does not say.
        if (StringUtils.hasText(parsed.username()) && !isSet(environment, USERNAME_VARIABLE)) {
            overrides.put(USERNAME_PROPERTY, parsed.username());
            if (StringUtils.hasText(parsed.password()) && !isSet(environment, PASSWORD_VARIABLE)) {
                overrides.put(PASSWORD_PROPERTY, parsed.password());
            }
        }
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
        }
    }

    private static boolean isSet(ConfigurableEnvironment environment, String variable) {
        return StringUtils.hasText(environment.getProperty(variable));
    }
}
