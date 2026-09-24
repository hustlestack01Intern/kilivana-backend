package com.kilivana.logistics.api;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateLogisticsJobRequest(
        @NotNull UUID orderId,
        @Size(max = 500) String notes,
        @NotNull @Future OffsetDateTime estimatedArrival,
        @NotNull UUID pickupAddressId,
        @NotNull UUID deliveryAddressId) {
}