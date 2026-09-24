package com.kilivana.orders.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID productId,
        @NotNull @DecimalMin("0.01") BigDecimal quantity,
        @NotBlank String currency) {
}