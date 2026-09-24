package com.kilivana.disputes.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveDisputeRequest(@NotBlank @Size(max = 2000) String resolutionNote) {
}