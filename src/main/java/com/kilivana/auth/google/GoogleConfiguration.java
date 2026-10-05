package com.kilivana.auth.google;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wires the Google surface, and only exists when it is enabled.
 *
 * <p>One {@code @ConfigurationProperties} binding rather than several {@code @Value} reads, so
 * the whole feature is switched by one property and one property is validated in one place.
 * Every other Google bean carries the same {@code @ConditionalOnProperty}, which means
 * disabling the feature leaves no verifier, no key fetcher and no client id list in the
 * context at all rather than leaving them inert.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kilivana.security.google", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GoogleConfiguration.class);

    /**
     * Validated in the constructor, so the prod refusal holds for a context that supplies its
     * own mail sender — the same reasoning as {@code PasswordResetMailConfiguration}.
     */
    public GoogleConfiguration(GoogleProperties properties, Environment environment) {
        properties.validate(environment);
        if (!properties.clientIds().isEmpty()) {
            log.info("Google sign-in is enabled for {} configured client id(s)", properties.clientIds().size());
        }
    }

    @Bean
    public EmailVerificationMailSender emailVerificationMailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            GoogleProperties properties) {
        return new SpringMailEmailVerificationMailSender(mailSenderProvider.getIfAvailable(), properties);
    }
}