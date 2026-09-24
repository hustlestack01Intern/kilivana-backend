package com.kilivana.messages.api;

import com.kilivana.messages.domain.ContactMessageStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ContactMessageResponse(
        UUID id,
        String name,
        String email,
        String subject,
        String body,
        ContactMessageStatus status,
        OffsetDateTime createdAt) {
}