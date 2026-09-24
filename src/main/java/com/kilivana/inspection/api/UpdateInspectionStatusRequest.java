package com.kilivana.inspection.api;

import com.kilivana.inspection.domain.InspectionStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateInspectionStatusRequest(@NotNull InspectionStatus status) {
}