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
@Table(name = "site_visit_requests")
public class SiteVisit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "farmer_id", nullable = false)
    private User farmer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private OffsetDateTime requestedAt;

    @Column(nullable = true)
    private OffsetDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SiteVisitStatus status;

    protected SiteVisit() {
    }

    public SiteVisit(User farmer, Product product) {
        this.farmer = farmer;
        this.product = product;
        this.requestedAt = OffsetDateTime.now();
        this.status = SiteVisitStatus.REQUESTED;
    }

    public User getFarmer() {
        return farmer;
    }

    public Product getProduct() {
        return product;
    }

    public OffsetDateTime getRequestedAt() {
        return requestedAt;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public SiteVisitStatus getStatus() {
        return status;
    }

    public void schedule(OffsetDateTime scheduledAt) {
        if (status != SiteVisitStatus.REQUESTED) {
            throw new BusinessConflictException("Only requested site visits can be scheduled");
        }
        this.scheduledAt = scheduledAt;
        this.status = SiteVisitStatus.SCHEDULED;
    }

    public void complete() {
        if (status != SiteVisitStatus.SCHEDULED) {
            throw new BusinessConflictException("Only scheduled site visits can be completed");
        }
        this.status = SiteVisitStatus.COMPLETED;
    }

    public void reject() {
        if (status == SiteVisitStatus.COMPLETED || status == SiteVisitStatus.REJECTED) {
            throw new BusinessConflictException("This site visit can no longer be rejected");
        }
        this.status = SiteVisitStatus.REJECTED;
    }
}