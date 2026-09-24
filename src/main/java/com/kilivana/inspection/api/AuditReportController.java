package com.kilivana.inspection.api;

import com.kilivana.inspection.service.AuditReportService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-reports")
public class AuditReportController {

    private final AuditReportService auditReportService;

    public AuditReportController(AuditReportService auditReportService) {
        this.auditReportService = auditReportService;
    }

    @PostMapping("/inspection/{inspectionId}")
    public ResponseEntity<AuditReportResponse> create(
            @PathVariable UUID inspectionId,
            @Valid @RequestBody CreateAuditReportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auditReportService.create(inspectionId, request));
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<AuditReportResponse> getById(@PathVariable UUID reportId) {
        return ResponseEntity.ok(auditReportService.getById(reportId));
    }

    @GetMapping("/inspection/{inspectionId}")
    public ResponseEntity<AuditReportResponse> getByInspectionId(@PathVariable UUID inspectionId) {
        return ResponseEntity.ok(auditReportService.getByInspectionId(inspectionId));
    }
}