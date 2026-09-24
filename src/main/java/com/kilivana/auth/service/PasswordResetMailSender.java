package com.kilivana.auth.service;

public interface PasswordResetMailSender {

    void sendResetLink(String recipient, String rawToken);
}
