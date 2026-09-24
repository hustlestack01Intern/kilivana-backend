package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kilivana.security.config.CorsConfigurationProvider;
import com.kilivana.security.config.CorsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.cors.CorsConfiguration;

class SecurityBoundaryTest {

    @Test
    void wildcardOriginsWithCredentialsAreRejected() {
        assertThatThrownBy(() -> new CorsConfigurationProvider().corsConfigurationSource(
                new CorsProperties(List.of("*"), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wildcard");
    }

    @Test
    void corsUsesOnlyConfiguredOrigins() {
        CorsConfigurationProvider provider = new CorsConfigurationProvider();
        var source = provider.corsConfigurationSource(new CorsProperties(
                List.of("https://app.example.test"),
                false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOriginPatterns()).containsExactly("https://app.example.test");
        assertThat(configuration.getAllowCredentials()).isFalse();
    }

    @Test
    void authenticationAndAuthorizationFailuresUseTheJsonEnvelopeAndRequestId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ApiSecurityErrorHandler handler = new ApiSecurityErrorHandler(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("X-Request-Id", "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(request, response, new BadCredentialsException("bad password"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("request-123");
        assertThat(objectMapper.readTree(response.getContentAsByteArray()).get("code").asText())
                .isEqualTo("AUTHENTICATION_REQUIRED");

        MockHttpServletResponse forbiddenResponse = new MockHttpServletResponse();
        handler.handle(request, forbiddenResponse, new AccessDeniedException("denied"));
        assertThat(forbiddenResponse.getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(forbiddenResponse.getContentAsByteArray()).get("code").asText())
                .isEqualTo("FORBIDDEN");
    }
}
