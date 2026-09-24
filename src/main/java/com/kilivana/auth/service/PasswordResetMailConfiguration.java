package com.kilivana.auth.service;

import com.kilivana.security.config.PasswordResetProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties(PasswordResetProperties.class)
public class PasswordResetMailConfiguration {

    @Bean
    @ConditionalOnMissingBean(PasswordResetMailSender.class)
    public PasswordResetMailSender passwordResetMailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            PasswordResetProperties properties) {
        properties.validate();
        return new SpringMailPasswordResetMailSender(mailSenderProvider.getIfAvailable(), properties);
    }
}
