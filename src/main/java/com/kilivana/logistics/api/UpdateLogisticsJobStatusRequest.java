package com.kilivana.logistics.api;

import com.kilivana.logistics.domain.LogisticsJobStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateLogisticsJobStatusRequest(@NotNull LogisticsJobStatus status) {
}