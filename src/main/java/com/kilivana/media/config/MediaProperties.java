package com.kilivana.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilivana.media")
public record MediaProperties(String rootDir, long maxFileSizeBytes) {

    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    public MediaProperties {
        maxFileSizeBytes = maxFileSizeBytes <= 0 ? DEFAULT_MAX_FILE_SIZE_BYTES : maxFileSizeBytes;
    }

    public String effectiveRootDir() {
        return rootDir == null || rootDir.isBlank() ? "./uploads" : rootDir;
    }
}
