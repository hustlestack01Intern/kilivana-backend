package com.kilivana.auth.google;

/**
 * Delivers the mailbox-proof link.
 *
 * <p>An interface with one production implementation and one test recorder, mirroring
 * {@code PasswordResetMailSender}. The recorder is how the integration suite observes that a
 * mail was sent, and to whom, without an SMTP server and without a Mockito spy on a bean that
 * is also used by password reset.
 */
public interface EmailVerificationMailSender {

    void sendVerificationLink(String recipient, String rawToken);
}