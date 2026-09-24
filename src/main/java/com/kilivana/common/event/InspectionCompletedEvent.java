package com.kilivana.common.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InspectionCompletedEvent(
        UUID inspectionId,
        UUID listingId,
        boolean passed,
        OffsetDateTime occurredAt) implements DomainEvent {
}