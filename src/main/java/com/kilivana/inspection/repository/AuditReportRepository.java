package com.kilivana.inspection.repository;

import com.kilivana.inspection.domain.AuditReport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditReportRepository extends JpaRepository<AuditReport, UUID> {

    @EntityGraph(attributePaths = {"inspection", "inspection.product"})
    Optional<AuditReport> findWithDetailsById(UUID id);

    @EntityGraph(attributePaths = {"inspection", "inspection.product"})
    Optional<AuditReport> findByInspectionId(UUID inspectionId);
}