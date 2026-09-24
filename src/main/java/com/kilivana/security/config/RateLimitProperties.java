package com.kilivana.security.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.security.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        String backend,
        int requestsPerMinute,
        int loginRequestsPerMinute,
        int registrationRequestsPerMinute,
        int forgotPasswordRequestsPerMinute,
        int adminBootstrapRequestsPerMinute,
        int maxLocalKeys) {

    public RateLimitProperties {
        backend = backend == null || backend.isBlank() ? "redis" : backend.trim().toLowerCase();
        requestsPerMinute = requestsPerMinute <= 0 ? 30 : requestsPerMinute;
        loginRequestsPerMinute = loginRequestsPerMinute <= 0 ? 5 : loginRequestsPerMinute;
        registrationRequestsPerMinute = registrationRequestsPerMinute <= 0 ? 5 : registrationRequestsPerMinute;
        forgotPasswordRequestsPerMinute = forgotPasswordRequestsPerMinute <= 0 ? 3 : forgotPasswordRequestsPerMinute;
        adminBootstrapRequestsPerMinute = adminBootstrapRequestsPerMinute <= 0 ? 3 : adminBootstrapRequestsPerMinute;
        maxLocalKeys = maxLocalKeys <= 0 ? 10_000 : maxLocalKeys;
    }

    public void validate() {
        if (!backend.equals("redis") && !backend.equals("memory")) {
            throw new IllegalStateException("Rate limit backend must be redis or memory");
        }
    }

    public List<RateLimitRule> rules() {
        return List.of(
                new RateLimitRule("login", loginRequestsPerMinute()),
                new RateLimitRule("register", registrationRequestsPerMinute()),
                new RateLimitRule("forgot-password", forgotPasswordRequestsPerMinute()),
                new RateLimitRule("admin-bootstrap", adminBootstrapRequestsPerMinute()));
    }

    public record RateLimitRule(String name, int limit) {
    }
}
