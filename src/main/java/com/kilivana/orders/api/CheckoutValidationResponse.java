package com.kilivana.orders.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CheckoutValidationResponse(
        UUID buyerId,
        int sellerCount,
        int itemCount,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal total,
        String currency,
        List<SellerTotals> sellers) {

    public record SellerTotals(
            UUID sellerId,
            String sellerName,
            BigDecimal subtotal,
            BigDecimal deliveryFee,
            BigDecimal total) {
    }
}