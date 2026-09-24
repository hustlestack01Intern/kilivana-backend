package com.kilivana.inspection.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_reports")
public class AuditReport extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, unique = true)
    private Inspection inspection;

    @Column(nullable = false, unique = true, length = 40)
    private String reportNumber;

    @Column(nullable = false, length = 2000)
    private String summary;

    @Column(nullable = true, length = 500)
    private String recommendation;

    @Column(nullable = false)
    private OffsetDateTime issuedAt;

    protected AuditReport() {
    }

    public AuditReport(Inspection inspection, String reportNumber, String summary, String recommendation) {
        this.inspection = inspection;
        this.reportNumber = reportNumber;
        this.summary = summary;
        this.recommendation = recommendation;
        this.issuedAt = OffsetDateTime.now();
    }

    public Inspection getInspection() {
        return inspection;
    }

    public String getReportNumber() {
        return reportNumber;
    }

    public String getSummary() {
        return summary;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }
}