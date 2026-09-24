package com.kilivana.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.security.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        long accessTokenMinutes,
        long refreshTokenDays,
        String secret) {
}