package com.kilivana.auth.service;

import com.kilivana.security.config.PasswordResetProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties(PasswordResetProperties.class)
public class PasswordResetMailConfiguration {

    /**
     * Validated in the constructor rather than inside the conditional mail sender bean, so the
     * prod invariant survives a context that supplies its own {@link PasswordResetMailSender}.
     */
    public PasswordResetMailConfiguration(PasswordResetProperties properties, Environment environment) {
        properties.validate(environment);
    }

    @Bean
    @ConditionalOnMissingBean(PasswordResetMailSender.class)
    public PasswordResetMailSender passwordResetMailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            PasswordResetProperties properties) {
        return new SpringMailPasswordResetMailSender(mailSenderProvider.getIfAvailable(), properties);
    }
}
