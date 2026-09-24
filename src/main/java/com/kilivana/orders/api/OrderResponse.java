package com.kilivana.orders.api;

import com.kilivana.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        UUID customerOrderId,
        UUID buyerId,
        UUID sellerId,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal totalAmount,
        String currency,
        OffsetDateTime createdAt,
        List<OrderItemResponse> items) {
}