package com.kilivana.audit.service;

import com.kilivana.audit.api.AuditLogResponse;
import com.kilivana.audit.domain.AuditLog;
import com.kilivana.audit.repository.AuditLogRepository;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            UserRepository userRepository,
            CurrentUser currentUser) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    public void record(String action, String entityType, UUID entityId, String details, String ipAddress) {
        User actor = null;
        AuthenticatedUser maybe = currentUser.maybe();
        if (maybe != null) {
            actor = userRepository.findById(maybe.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        }
        auditLogRepository.save(new AuditLog(actor, action, entityType, entityId, details, ipAddress));
    }

    public List<AuditLogResponse> list(String actorId, String entityType, String entityId, String action) {
        requireAdmin();
        List<AuditLog> logs;
        if (entityType != null && entityId != null) {
            logs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, UUID.fromString(entityId));
        } else if (actorId != null) {
            logs = auditLogRepository.findByActorIdOrderByOccurredAtDesc(UUID.fromString(actorId));
        } else if (action != null) {
            logs = auditLogRepository.findByActionOrderByOccurredAtDesc(action);
        } else {
            logs = auditLogRepository.findAllByOrderByOccurredAtDesc();
        }
        return logs.stream().map(this::toResponse).toList();
    }

    private void requireAdmin() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can view audit logs");
        }
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getActor() == null ? null : log.getActor().getId(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getDetails(),
                log.getIpAddress(),
                log.getOccurredAt());
    }
}