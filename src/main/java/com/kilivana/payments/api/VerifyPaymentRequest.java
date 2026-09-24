package com.kilivana.payments.api;

import jakarta.validation.constraints.NotBlank;

public record VerifyPaymentRequest(@NotBlank String idempotencyKey) {
}
