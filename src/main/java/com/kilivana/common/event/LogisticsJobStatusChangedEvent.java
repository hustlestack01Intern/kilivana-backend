package com.kilivana.common.event;

import com.kilivana.logistics.domain.LogisticsJobStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LogisticsJobStatusChangedEvent(
        UUID jobId,
        UUID orderId,
        LogisticsJobStatus previousStatus,
        LogisticsJobStatus newStatus,
        OffsetDateTime occurredAt) implements DomainEvent {
}