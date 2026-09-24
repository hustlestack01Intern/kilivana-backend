package com.kilivana.inspection.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateSiteVisitRequest(@NotNull UUID productId) {
}