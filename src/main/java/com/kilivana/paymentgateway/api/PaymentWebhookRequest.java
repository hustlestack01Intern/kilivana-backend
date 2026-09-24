package com.kilivana.paymentgateway.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PaymentWebhookRequest(
        @NotNull WebhookEventType eventType,
        @NotBlank String externalId,
        @NotBlank String reference) {
}