package com.kilivana.inspection.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VisitPhotoResponse(
        UUID id,
        UUID inspectionId,
        String fileName,
        String contentType,
        long sizeBytes,
        OffsetDateTime uploadedAt,
        String downloadUrl) {
}