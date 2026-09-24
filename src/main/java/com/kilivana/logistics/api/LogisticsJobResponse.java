package com.kilivana.logistics.api;

import com.kilivana.logistics.domain.LogisticsJobStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LogisticsJobResponse(
        UUID jobId,
        UUID orderId,
        LogisticsJobStatus status,
        UUID driverId,
        String driverName,
        String notes,
        OffsetDateTime estimatedArrival,
        UUID pickupAddressId,
        UUID deliveryAddressId,
        OffsetDateTime podSubmittedAt,
        String deliveredTo,
        OffsetDateTime createdAt) {
}