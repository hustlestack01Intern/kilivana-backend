package com.kilivana.disputes.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.orders.domain.Order;
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
@Table(name = "disputes")
public class Dispute extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raised_by_id", nullable = false)
    private User raisedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DisputeStatus status;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = true, length = 2000)
    private String resolutionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    private User resolvedBy;

    @Column(nullable = true)
    private OffsetDateTime resolvedAt;

    protected Dispute() {
    }

    public Dispute(Order order, User raisedBy, String subject, String description) {
        this.order = order;
        this.raisedBy = raisedBy;
        this.subject = subject;
        this.description = description;
        this.status = DisputeStatus.OPEN;
    }

    public Order getOrder() {
        return order;
    }

    public User getRaisedBy() {
        return raisedBy;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public User getResolvedBy() {
        return resolvedBy;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void resolve(User resolver, String note) {
        if (status == DisputeStatus.RESOLVED) {
            throw new BusinessConflictException("This dispute is already resolved");
        }
        this.status = DisputeStatus.RESOLVED;
        this.resolvedBy = resolver;
        this.resolutionNote = note;
        this.resolvedAt = OffsetDateTime.now();
    }

    public void escalate() {
        if (status == DisputeStatus.RESOLVED) {
            throw new BusinessConflictException("A resolved dispute cannot be escalated");
        }
        this.status = DisputeStatus.ESCALATED;
    }
}