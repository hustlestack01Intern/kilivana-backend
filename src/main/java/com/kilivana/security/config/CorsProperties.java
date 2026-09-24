package com.kilivana.security.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.security.cors")
public record CorsProperties(List<String> allowedOrigins, boolean allowCredentials) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .map(String::trim)
                        .toList();
    }

    public void validate() {
        if (allowCredentials && allowedOrigins.contains("*")) {
            throw new IllegalStateException("Wildcard CORS origins cannot be used with credentials");
        }
    }
}
