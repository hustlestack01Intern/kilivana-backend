package com.kilivana.products.api;

import java.util.UUID;

public record ProductImageResponse(
        UUID imageId,
        String fileName,
        String contentType,
        long sizeBytes,
        String url,
        int sortOrder) {
}