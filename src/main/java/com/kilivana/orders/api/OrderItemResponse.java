package com.kilivana.orders.api;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        UUID productId,
        String productTitle,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal) {
}