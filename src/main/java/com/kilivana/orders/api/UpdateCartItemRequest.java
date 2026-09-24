package com.kilivana.orders.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdateCartItemRequest(@NotNull @DecimalMin("0.01") BigDecimal quantity) {
}