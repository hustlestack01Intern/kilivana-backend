package com.kilivana.inspection.api;

import com.kilivana.inspection.domain.InspectionResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record SubmitInspectionResultRequest(
        @NotNull InspectionResult result,
        @Min(1) @Max(5) Integer rating,
        @Size(max = 2000) String findings,
        OffsetDateTime nextInspectionDate) {
}