package com.kilivana.common.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ContactMessageReceivedEvent(
        UUID messageId,
        String email,
        String subject,
        OffsetDateTime occurredAt) implements DomainEvent {
}