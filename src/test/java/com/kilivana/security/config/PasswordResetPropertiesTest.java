package com.kilivana.security.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PasswordResetPropertiesTest {

    @Test
    @DisplayName("simulated delivery is refused on the prod profile")
    void refusesSimulatedDeliveryOnProd() {
        PasswordResetProperties properties =
                new PasswordResetProperties(Duration.ofMinutes(15), "https://example.test/reset", true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> properties.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulate-delivery");
    }

    @Test
    @DisplayName("the prod refusal holds when prod is one profile among several")
    void refusesSimulatedDeliveryWhenProdIsNotTheOnlyProfile() {
        PasswordResetProperties properties =
                new PasswordResetProperties(Duration.ofMinutes(15), "https://example.test/reset", true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("render", "prod", "metrics");

        assertThatThrownBy(() -> properties.validate(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("simulated delivery stays available off prod, where the token is not a takeover")
    void allowsSimulatedDeliveryOffProd() {
        PasswordResetProperties properties =
                new PasswordResetProperties(Duration.ofMinutes(15), "https://example.test/reset", true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThatCode(() -> properties.validate(environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a real mailer on prod is unaffected by the simulated-delivery guard")
    void allowsRealDeliveryOnProd() {
        PasswordResetProperties properties =
                new PasswordResetProperties(Duration.ofMinutes(15), "https://example.test/reset", false);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatCode(() -> properties.validate(environment)).doesNotThrowAnyException();
    }
}
