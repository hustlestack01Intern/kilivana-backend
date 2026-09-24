package com.kilivana.inspection.api;

import com.kilivana.inspection.domain.InspectionResult;
import com.kilivana.inspection.domain.InspectionStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InspectionResponse(
        UUID id,
        UUID inspectorId,
        UUID productId,
        String productTitle,
        InspectionStatus status,
        InspectionResult result,
        UUID checklistId,
        Integer rating,
        String findings,
        OffsetDateTime scheduledAt,
        OffsetDateTime performedAt,
        OffsetDateTime nextInspectionDate) {
}