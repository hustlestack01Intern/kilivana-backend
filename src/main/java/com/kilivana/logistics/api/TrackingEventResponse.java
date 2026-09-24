package com.kilivana.logistics.api;

import com.kilivana.logistics.domain.LogisticsJobStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TrackingEventResponse(
        UUID trackingEventId,
        UUID jobId,
        LogisticsJobStatus status,
        Double latitude,
        Double longitude,
        String locationName,
        String note,
        OffsetDateTime loggedAt) {
}