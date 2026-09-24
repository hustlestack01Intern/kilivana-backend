package com.kilivana.disputes.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateDisputeRequest(
        @NotNull UUID orderId,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 2000) String description) {
}