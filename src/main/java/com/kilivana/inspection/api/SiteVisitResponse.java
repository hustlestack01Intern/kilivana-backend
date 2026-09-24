package com.kilivana.inspection.api;

import com.kilivana.inspection.domain.SiteVisitStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SiteVisitResponse(
        UUID id,
        UUID farmerId,
        UUID productId,
        String productTitle,
        OffsetDateTime requestedAt,
        OffsetDateTime scheduledAt,
        SiteVisitStatus status) {
}