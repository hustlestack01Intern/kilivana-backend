package com.kilivana.security.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.security.proxy")
public record ProxyProperties(boolean trustForwardedHeaders, List<String> trustedProxies) {

    public ProxyProperties {
        trustedProxies = trustedProxies == null
                ? List.of()
                : trustedProxies.stream().filter(proxy -> proxy != null && !proxy.isBlank()).toList();
    }

    public void validate() {
        if (trustForwardedHeaders && trustedProxies.isEmpty()) {
            throw new IllegalStateException("Trusted proxy addresses are required when forwarded headers are enabled");
        }
    }
}
