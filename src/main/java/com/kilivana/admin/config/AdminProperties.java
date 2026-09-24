package com.kilivana.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.security.admin")
public record AdminProperties(String bootstrapKey) {

    public boolean isEnabled() {
        return bootstrapKey != null && !bootstrapKey.isBlank();
    }
}
