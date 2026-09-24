package com.kilivana.orders.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CheckoutValidationRequest(
        @NotNull UUID shippingAddressId,
        @NotNull UUID pickupAddressId,
        @NotBlank String currency) {
}