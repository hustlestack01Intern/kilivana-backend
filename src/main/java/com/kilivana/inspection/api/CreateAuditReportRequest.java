package com.kilivana.inspection.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAuditReportRequest(
        @NotBlank @Size(max = 2000) String summary,
        @Size(max = 500) String recommendation) {
}