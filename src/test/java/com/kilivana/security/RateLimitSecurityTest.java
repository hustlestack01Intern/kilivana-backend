package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kilivana.security.config.ProxyProperties;
import com.kilivana.security.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class RateLimitSecurityTest {

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private FilterChain filterChain;

    @Test
    void appliesEndpointSpecificLimitAndReturnsRetryAfter() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(
                true,
                "memory",
                30,
                1,
                5,
                3,
                3,
                100);
        RateLimitFilter filter = new RateLimitFilter(
                properties,
                rateLimiter,
                new ClientIpResolver(new ProxyProperties(false, List.of())),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        when(rateLimiter.tryConsume(anyString(), eq(1), any())).thenReturn(RateLimitResult.limited(9));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Request-Id", "rate-request");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("9");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("rate-request");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void forwardedAddressIsUsedOnlyBehindAConfiguredTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(new ProxyProperties(true, List.of("10.0.0.0/8")));
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("10.1.2.3");
        trusted.addHeader("X-Forwarded-For", "198.51.100.7, 10.2.3.4");
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("192.0.2.10");
        untrusted.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(resolver.resolve(trusted)).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(untrusted)).isEqualTo("192.0.2.10");
    }
}
