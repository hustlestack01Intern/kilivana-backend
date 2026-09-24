package com.kilivana.products.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateProductRequest(
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 1000) String description,
        @NotBlank @Size(max = 30) String unit,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
        @NotNull @DecimalMin("0") BigDecimal availableQuantity,
        UUID categoryId,
        @DecimalMin("0") BigDecimal lowStockThreshold,
        @DecimalMin("0.01") BigDecimal minimumOrderQuantity) {
}