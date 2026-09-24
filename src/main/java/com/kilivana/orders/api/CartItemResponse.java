package com.kilivana.orders.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CartItemResponse(
        UUID cartItemId,
        UUID productId,
        String productTitle,
        UUID sellerId,
        String sellerName,
        BigDecimal unitPrice,
        BigDecimal quantity,
        BigDecimal lineTotal,
        OffsetDateTime addedAt) {
}