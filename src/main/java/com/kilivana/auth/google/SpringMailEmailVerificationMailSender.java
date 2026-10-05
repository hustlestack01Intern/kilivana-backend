package com.kilivana.auth.google;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The SMTP implementation.
 *
 * <p>Constructed with a possibly-absent {@link JavaMailSender} and refuses at send time rather
 * than at construction, so a context that supplies its own implementation — or a test that
 * never sends — does not have to configure SMTP to exist.
 */
public class SpringMailEmailVerificationMailSender implements EmailVerificationMailSender {

    private final JavaMailSender mailSender;
    private final GoogleProperties properties;

    public SpringMailEmailVerificationMailSender(JavaMailSender mailSender, GoogleProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendVerificationLink(String recipient, String rawToken) {
        if (mailSender == null) {
            throw new IllegalStateException("Email verification delivery is not configured");
        }
        String verificationUrl = UriComponentsBuilder.fromUriString(properties.frontendUrl())
                .queryParam("token", rawToken)
                .build()
                .encode()
                .toUriString();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(recipient);
        message.setSubject("Verify your email to finish signing up for Kilivana");
        message.setText("Use this link to verify your email and finish creating your Kilivana account: "
                + verificationUrl
                + "\nThe link expires shortly and can only be used once. "
                + "If you did not try to sign up, you can ignore this message.");
        mailSender.send(message);
    }
}