package com.kilivana.paymentgateway;

import java.math.BigDecimal;

public record PaymentIntent(
        String externalId,
        String idempotencyKey,
        BigDecimal amount,
        String currency) {
}