package com.kilivana.common.event;

import com.kilivana.orders.domain.OrderStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderStatusChangedEvent(
        UUID orderId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        OffsetDateTime occurredAt) implements DomainEvent {
}