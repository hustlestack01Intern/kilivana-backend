package com.kilivana.inspection.service;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.inspection.api.AuditReportResponse;
import com.kilivana.inspection.api.CreateAuditReportRequest;
import com.kilivana.inspection.domain.AuditReport;
import com.kilivana.inspection.repository.AuditReportRepository;
import com.kilivana.inspection.domain.Inspection;
import com.kilivana.inspection.domain.InspectionStatus;
import com.kilivana.inspection.repository.InspectionRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.UserRole;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditReportService {

    private final AuditReportRepository auditReportRepository;
    private final InspectionRepository inspectionRepository;
    private final CurrentUser currentUser;

    public AuditReportService(
            AuditReportRepository auditReportRepository,
            InspectionRepository inspectionRepository,
            CurrentUser currentUser) {
        this.auditReportRepository = auditReportRepository;
        this.inspectionRepository = inspectionRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public AuditReportResponse create(UUID inspectionId, CreateAuditReportRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR) {
            throw new UnauthorizedOperationException("Only inspectors can issue audit reports");
        }
        Inspection inspection = inspectionRepository.findWithActorsById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
        if (!inspection.getInspector().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only report on inspections you performed");
        }
        if (inspection.getStatus() != InspectionStatus.PASSED && inspection.getStatus() != InspectionStatus.FAILED) {
            throw new BusinessConflictException("Audit reports can only be issued for concluded inspections");
        }
        if (auditReportRepository.findByInspectionId(inspectionId).isPresent()) {
            throw new BusinessConflictException("An audit report already exists for this inspection");
        }

        AuditReport report = new AuditReport(
                inspection,
                "AR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                request.summary().trim(),
                request.recommendation() == null ? null : request.recommendation().trim());
        auditReportRepository.save(report);
        return toResponse(report);
    }

    public AuditReportResponse getById(UUID reportId) {
        requireViewer();
        return toResponse(auditReportRepository.findWithDetailsById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Audit report not found")));
    }

    public AuditReportResponse getByInspectionId(UUID inspectionId) {
        requireViewer();
        return toResponse(auditReportRepository.findByInspectionId(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Audit report not found")));
    }

    private void requireViewer() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR
                && actor.getRole() != UserRole.SUPPLIER
                && actor.getRole() != UserRole.FARMER) {
            throw new UnauthorizedOperationException("Only inspectors and sellers can view audit reports");
        }
    }

    private AuditReportResponse toResponse(AuditReport report) {
        return new AuditReportResponse(
                report.getId(),
                report.getInspection().getId(),
                report.getInspection().getProduct().getId(),
                report.getInspection().getProduct().getTitle(),
                report.getReportNumber(),
                report.getSummary(),
                report.getRecommendation(),
                report.getIssuedAt());
    }
}