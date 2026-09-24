package com.kilivana.badges.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AwardBadgeRequest(
        @NotNull UUID userId,
        @NotBlank @Size(max = 100) String badgeCode,
        @Size(max = 500) String note) {
}