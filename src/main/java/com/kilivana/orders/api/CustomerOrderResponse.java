package com.kilivana.orders.api;

import com.kilivana.orders.domain.CustomerOrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CustomerOrderResponse(
        UUID customerOrderId,
        UUID buyerId,
        CustomerOrderStatus status,
        BigDecimal totalAmount,
        String currency,
        UUID shippingAddressId,
        UUID pickupAddressId,
        OffsetDateTime createdAt,
        List<OrderResponse> subOrders) {
}