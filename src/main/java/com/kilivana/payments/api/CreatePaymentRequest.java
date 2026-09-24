package com.kilivana.payments.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreatePaymentRequest(
        @NotNull UUID orderId,
        @NotBlank String provider,
        @NotBlank String idempotencyKey) {
}
