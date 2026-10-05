package com.kilivana.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.security.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        String backend,
        int loginRequestsPerMinute,
        int registrationRequestsPerMinute,
        int forgotPasswordRequestsPerMinute,
        int adminBootstrapRequestsPerMinute,
        int googleHandshakeRequestsPerMinute,
        int googleVerifyEmailRequestsPerMinute,
        int googleOnboardRequestsPerMinute,
        int googleLinkRequestsPerMinute,
        int loginAttemptsPerAccountPerWindow,
        int loginAccountWindowMinutes,
        int maxLocalKeys) {

    public RateLimitProperties {
        backend = backend == null || backend.isBlank() ? "redis" : normalizeBackend(backend);
        loginRequestsPerMinute = loginRequestsPerMinute <= 0 ? 5 : loginRequestsPerMinute;
        registrationRequestsPerMinute = registrationRequestsPerMinute <= 0 ? 5 : registrationRequestsPerMinute;
        forgotPasswordRequestsPerMinute = forgotPasswordRequestsPerMinute <= 0 ? 3 : forgotPasswordRequestsPerMinute;
        adminBootstrapRequestsPerMinute = adminBootstrapRequestsPerMinute <= 0 ? 3 : adminBootstrapRequestsPerMinute;
        // The handshake is the widest door in the system: unauthenticated, and on the failure
        // path it reaches a third party's HTTPS endpoint. Ten a minute per address is roughly
        // what a person with a slow connection and a misconfigured client actually uses.
        googleHandshakeRequestsPerMinute =
                googleHandshakeRequestsPerMinute <= 0 ? 10 : googleHandshakeRequestsPerMinute;
        googleVerifyEmailRequestsPerMinute =
                googleVerifyEmailRequestsPerMinute <= 0 ? 5 : googleVerifyEmailRequestsPerMinute;
        // Onboarding creates a row, so it carries the registration budget rather than a looser
        // one of its own.
        googleOnboardRequestsPerMinute =
                googleOnboardRequestsPerMinute <= 0 ? 5 : googleOnboardRequestsPerMinute;
        // Account link takes a password, so it is held to the login budget.
        googleLinkRequestsPerMinute = googleLinkRequestsPerMinute <= 0 ? 5 : googleLinkRequestsPerMinute;
        loginAttemptsPerAccountPerWindow = loginAttemptsPerAccountPerWindow <= 0 ? 10 : loginAttemptsPerAccountPerWindow;
        loginAccountWindowMinutes = loginAccountWindowMinutes <= 0 ? 15 : loginAccountWindowMinutes;
        maxLocalKeys = maxLocalKeys <= 0 ? 10_000 : maxLocalKeys;
    }

    /**
     * The two rate limiter beans are selected by exact string match, so an unrecognised value
     * leaves no {@code RateLimiter} candidate and the context fails to start with an
     * unhelpful "No qualifying bean of type RateLimiter" error. Normalising here means the
     * failure surfaces at property binding with the list of values that actually work.
     */
    private static String normalizeBackend(String backend) {
        return switch (backend.trim().toLowerCase()) {
            case "redis" -> "redis";
            case "memory", "inmemory", "in-memory", "local" -> "memory";
            default -> throw new IllegalArgumentException(
                    "kilivana.security.rate-limit.backend must be 'redis' or 'memory', but was '"
                            + backend + "'");
        };
    }

    public void validate() {
        if (!backend.equals("redis") && !backend.equals("memory")) {
            throw new IllegalStateException("Rate limit backend must be redis or memory");
        }
    }
}
