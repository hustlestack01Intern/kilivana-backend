package com.kilivana.inspection.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditReportResponse(
        UUID id,
        UUID inspectionId,
        UUID productId,
        String productTitle,
        String reportNumber,
        String summary,
        String recommendation,
        OffsetDateTime issuedAt) {
}