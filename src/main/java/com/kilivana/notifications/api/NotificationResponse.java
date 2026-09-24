package com.kilivana.notifications.api;

import com.kilivana.notifications.domain.NotificationType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        NotificationType type,
        String title,
        String body,
        boolean read,
        OffsetDateTime readAt,
        String link,
        OffsetDateTime createdAt) {
}