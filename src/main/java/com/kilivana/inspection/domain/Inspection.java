package com.kilivana.inspection.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.products.domain.Product;
import com.kilivana.users.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "inspections")
public class Inspection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspector_id", nullable = false)
    private User inspector;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checklist_id")
    private InspectionChecklist checklist;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InspectionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = true, length = 30)
    private InspectionResult result;

    @Column(nullable = true)
    private Integer rating;

    @Column(nullable = true, length = 2000)
    private String findings;

    @Column(nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(nullable = true)
    private OffsetDateTime performedAt;

    @Column(nullable = true)
    private OffsetDateTime nextInspectionDate;

    protected Inspection() {
    }

    public Inspection(User inspector, Product product, InspectionChecklist checklist, OffsetDateTime scheduledAt) {
        this.inspector = inspector;
        this.product = product;
        this.checklist = checklist;
        this.scheduledAt = scheduledAt;
        this.status = InspectionStatus.SCHEDULED;
    }

    public User getInspector() {
        return inspector;
    }

    public Product getProduct() {
        return product;
    }

    public InspectionChecklist getChecklist() {
        return checklist;
    }

    public InspectionStatus getStatus() {
        return status;
    }

    public InspectionResult getResult() {
        return result;
    }

    public Integer getRating() {
        return rating;
    }

    public String getFindings() {
        return findings;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public OffsetDateTime getPerformedAt() {
        return performedAt;
    }

    public OffsetDateTime getNextInspectionDate() {
        return nextInspectionDate;
    }

    public void markInProgress() {
        if (status != InspectionStatus.SCHEDULED) {
            throw new BusinessConflictException("Only scheduled inspections can be started");
        }
        this.status = InspectionStatus.IN_PROGRESS;
    }

    public void conclude(InspectionResult result, Integer rating, String findings, OffsetDateTime nextInspectionDate) {
        if (status != InspectionStatus.IN_PROGRESS) {
            throw new BusinessConflictException("Only in-progress inspections can be concluded");
        }
        if (result == null) {
            throw new BusinessConflictException("A result is required when concluding an inspection");
        }
        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessConflictException("Rating must be between 1 and 5");
        }
        if (result == InspectionResult.CHANGES_REQUIRED && nextInspectionDate == null) {
            throw new BusinessConflictException("A next inspection date is required when requesting changes");
        }
        this.rating = rating;
        this.findings = findings;
        this.result = result;
        this.status = result == InspectionResult.APPROVED ? InspectionStatus.PASSED : InspectionStatus.FAILED;
        this.performedAt = OffsetDateTime.now();
        this.nextInspectionDate = nextInspectionDate;
    }

    public boolean isPassed() {
        return status == InspectionStatus.PASSED || result == InspectionResult.APPROVED;
    }
}