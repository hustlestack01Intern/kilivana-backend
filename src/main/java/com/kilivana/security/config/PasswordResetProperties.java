package com.kilivana.security.config;

import java.time.Duration;
import java.util.Arrays;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

@ConfigurationProperties(prefix = "kilivana.security.password-reset")
public record PasswordResetProperties(
        Duration tokenTtl,
        String frontendUrl,
        boolean simulateDelivery) {

    private static final String PRODUCTION_PROFILE = "prod";

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

    /**
     * Simulated delivery hands the reset token to whoever called {@code /forgot-password} in the
     * HTTP response. That is an account-takeover primitive, and it is exactly the flag a platform
     * would have left on after a local convenience — so it is refused here rather than in a
     * profile file. An environment variable outranks every property file, which means a
     * profile-based assertion can be overridden into uselessness; the check reads the active
     * profiles at bean construction instead.
     */
    public void validate(Environment environment) {
        validate();
        if (simulateDelivery && Arrays.asList(environment.getActiveProfiles()).contains(PRODUCTION_PROFILE)) {
            throw new IllegalStateException("kilivana.security.password-reset.simulate-delivery cannot be "
                    + "enabled on the 'prod' profile: it returns password reset tokens to the caller");
        }
    }
}
