package com.kilivana.security.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.security.password-reset")
public record PasswordResetProperties(Duration tokenTtl, String frontendUrl) {

    public PasswordResetProperties {
        tokenTtl = tokenTtl == null ? Duration.ofMinutes(15) : tokenTtl;
        frontendUrl = frontendUrl == null || frontendUrl.isBlank()
                ? "http://localhost:3000/reset-password"
                : frontendUrl.trim();
    }

    public void validate() {
        if (tokenTtl.compareTo(Duration.ofMinutes(1)) < 0
                || tokenTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("Password reset token expiry must be between one minute and one hour");
        }
    }
}
