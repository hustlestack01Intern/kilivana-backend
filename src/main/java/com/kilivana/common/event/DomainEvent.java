package com.kilivana.common.event;

import java.time.OffsetDateTime;

public interface DomainEvent {

    OffsetDateTime occurredAt();
}