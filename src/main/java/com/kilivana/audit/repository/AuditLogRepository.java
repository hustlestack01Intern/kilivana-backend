package com.kilivana.audit.repository;

import com.kilivana.audit.domain.AuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findAllByOrderByOccurredAtDesc();

    List<AuditLog> findByActorIdOrderByOccurredAtDesc(UUID actorId);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, UUID entityId);

    List<AuditLog> findByActionOrderByOccurredAtDesc(String action);
}