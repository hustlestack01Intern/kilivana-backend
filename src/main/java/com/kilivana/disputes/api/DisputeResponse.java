package com.kilivana.disputes.api;

import com.kilivana.disputes.domain.DisputeStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DisputeResponse(
        UUID disputeId,
        UUID orderId,
        UUID raisedById,
        DisputeStatus status,
        String subject,
        String description,
        String resolutionNote,
        UUID resolvedById,
        OffsetDateTime resolvedAt,
        OffsetDateTime createdAt) {
}