package com.kilivana.orders.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record AddToCartRequest(
        @NotNull UUID productId,
        @NotNull @DecimalMin("0.01") BigDecimal quantity) {
}