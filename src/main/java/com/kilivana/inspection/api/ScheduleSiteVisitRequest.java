package com.kilivana.inspection.api;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record ScheduleSiteVisitRequest(
        @NotNull @Future(message = "scheduledAt must be in the future") OffsetDateTime scheduledAt) {
}