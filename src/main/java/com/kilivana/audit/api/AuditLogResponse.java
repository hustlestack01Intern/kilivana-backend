package com.kilivana.audit.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogResponse(
        UUID auditLogId,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        String details,
        String ipAddress,
        OffsetDateTime occurredAt) {
}