package com.kilivana.logistics.api;

import com.kilivana.logistics.domain.LogisticsJobStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TrackingEventRequest(
        @NotNull LogisticsJobStatus status,
        Double latitude,
        Double longitude,
        @Size(max = 200) String locationName,
        @Size(max = 500) String note) {
}