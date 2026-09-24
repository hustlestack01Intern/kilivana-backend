package com.kilivana.logistics.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignDriverRequest(@NotNull UUID driverId) {
}