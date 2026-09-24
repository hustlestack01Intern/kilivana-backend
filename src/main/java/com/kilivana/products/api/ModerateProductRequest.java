package com.kilivana.products.api;

import com.kilivana.products.domain.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record ModerateProductRequest(@NotNull ProductStatus status) {
}