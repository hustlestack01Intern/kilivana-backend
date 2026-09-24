package com.kilivana.inspection.api;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateInspectionRequest(
        @NotNull UUID productId,
        UUID checklistId,
        @NotNull @Future OffsetDateTime scheduledAt) {
}