package com.kilivana.auth.service;

import com.kilivana.security.config.PasswordResetProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.util.UriComponentsBuilder;

public class SpringMailPasswordResetMailSender implements PasswordResetMailSender {

    private final JavaMailSender mailSender;
    private final PasswordResetProperties properties;

    public SpringMailPasswordResetMailSender(JavaMailSender mailSender, PasswordResetProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendResetLink(String recipient, String rawToken) {
        if (mailSender == null) {
            throw new IllegalStateException("Password reset email delivery is not configured");
        }
        String resetUrl = UriComponentsBuilder.fromUriString(properties.frontendUrl())
                .queryParam("token", rawToken)
                .queryParam("email", recipient)
                .build()
                .encode()
                .toUriString();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(recipient);
        message.setSubject("Reset your Kilivana password");
        message.setText("Use this link to reset your password: " + resetUrl
                + "\nThe link expires shortly and can only be used once.");
        mailSender.send(message);
    }
}
