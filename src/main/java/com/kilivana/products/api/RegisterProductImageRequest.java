package com.kilivana.products.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterProductImageRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 100) String contentType,
        long sizeBytes,
        @NotBlank @Size(max = 500) String url) {
}