package com.kilivana.security;

import com.kilivana.security.config.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    public RateLimitFilter rateLimitFilter(
            RateLimitProperties properties,
            RateLimiter rateLimiter,
            ClientIpResolver clientIpResolver,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        properties.validate();
        return new RateLimitFilter(properties, rateLimiter, clientIpResolver, objectMapper);
    }
}
