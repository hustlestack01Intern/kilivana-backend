package com.kilivana.payments.api;

import com.kilivana.payments.domain.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        UUID orderId,
        PaymentStatus status,
        String provider,
        BigDecimal amount,
        String idempotencyKey,
        String externalId,
        String gatewayReference) {
}