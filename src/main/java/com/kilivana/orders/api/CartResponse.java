package com.kilivana.orders.api;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        BigDecimal totalAmount,
        String currency,
        List<CartItemResponse> items) {
}