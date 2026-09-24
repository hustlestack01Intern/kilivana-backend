package com.kilivana.common.event;

import com.kilivana.payments.domain.PaymentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentStatusChangedEvent(
        UUID paymentId,
        UUID orderId,
        PaymentStatus previousStatus,
        PaymentStatus newStatus,
        OffsetDateTime occurredAt) implements DomainEvent {
}