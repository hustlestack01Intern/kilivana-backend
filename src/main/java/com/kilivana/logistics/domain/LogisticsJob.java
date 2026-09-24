package com.kilivana.logistics.domain;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "logistics_jobs")
public class LogisticsJob extends BaseEntity {

    private static final Map<LogisticsJobStatus, Set<LogisticsJobStatus>> ALLOWED_TRANSITIONS = Map.of(
            LogisticsJobStatus.PENDING_ACCEPTANCE, Set.of(LogisticsJobStatus.ACCEPTED, LogisticsJobStatus.CANCELLED),
            LogisticsJobStatus.ACCEPTED, Set.of(LogisticsJobStatus.AT_PICKUP, LogisticsJobStatus.CANCELLED),
            LogisticsJobStatus.AT_PICKUP, Set.of(LogisticsJobStatus.PICKED_UP, LogisticsJobStatus.FAILED),
            LogisticsJobStatus.PICKED_UP, Set.of(LogisticsJobStatus.IN_TRANSIT, LogisticsJobStatus.FAILED),
            LogisticsJobStatus.IN_TRANSIT, Set.of(LogisticsJobStatus.DELIVERED, LogisticsJobStatus.FAILED),
            LogisticsJobStatus.DELIVERED, Set.of(),
            LogisticsJobStatus.FAILED, Set.of(),
            LogisticsJobStatus.CANCELLED, Set.of());

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private User driver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LogisticsJobStatus status;

    @Column(nullable = true, length = 500)
    private String notes;

    @Column(nullable = false)
    private OffsetDateTime estimatedArrival;

    @Column(nullable = true)
    private OffsetDateTime deliveredAt;

    @Column(nullable = true)
    private UUID pickupAddressId;

    @Column(nullable = true)
    private UUID deliveryAddressId;

    @Column(nullable = true, length = 255)
    private String podStorageKey;

    @Column(nullable = true, length = 255)
    private String podFileName;

    @Column(nullable = true, length = 100)
    private String podContentType;

    @Column(nullable = true)
    private OffsetDateTime podSubmittedAt;

    @Column(nullable = true, length = 150)
    private String deliveredTo;

    protected LogisticsJob() {
    }

    public LogisticsJob(Order order, String notes, OffsetDateTime estimatedArrival,
                        UUID pickupAddressId, UUID deliveryAddressId) {
        this.order = order;
        this.status = LogisticsJobStatus.PENDING_ACCEPTANCE;
        this.notes = notes;
        this.estimatedArrival = estimatedArrival;
        this.pickupAddressId = pickupAddressId;
        this.deliveryAddressId = deliveryAddressId;
    }

    public Order getOrder() {
        return order;
    }

    public User getDriver() {
        return driver;
    }

    public LogisticsJobStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public OffsetDateTime getEstimatedArrival() {
        return estimatedArrival;
    }

    public OffsetDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public UUID getPickupAddressId() {
        return pickupAddressId;
    }

    public UUID getDeliveryAddressId() {
        return deliveryAddressId;
    }

    public String getPodStorageKey() {
        return podStorageKey;
    }

    public String getPodFileName() {
        return podFileName;
    }

    public String getPodContentType() {
        return podContentType;
    }

    public OffsetDateTime getPodSubmittedAt() {
        return podSubmittedAt;
    }

    public String getDeliveredTo() {
        return deliveredTo;
    }

    public void updateStatus(LogisticsJobStatus nextStatus, User actor) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(nextStatus)) {
            throw new BusinessConflictException("Invalid logistics job status transition");
        }
        if (nextStatus == LogisticsJobStatus.ACCEPTED && actor.getRole().name().equals("DRIVER")) {
            if (driver != null && !driver.getId().equals(actor.getId())) {
                throw new BusinessConflictException("This job has already been accepted by another driver");
            }
            this.driver = actor;
            this.status = nextStatus;
        } else if (nextStatus == LogisticsJobStatus.ACCEPTED) {
            throw new BusinessConflictException("Only a driver can accept a logistics job");
        } else {
            if (nextStatus == LogisticsJobStatus.DELIVERED && podSubmittedAt == null) {
                throw new BusinessConflictException("Proof of delivery must be submitted before marking delivered");
            }
            this.status = nextStatus;
            if (nextStatus == LogisticsJobStatus.DELIVERED) {
                this.deliveredAt = OffsetDateTime.now();
            }
        }
    }

    public void assignDriver(User driver) {
        if (status != LogisticsJobStatus.PENDING_ACCEPTANCE) {
            throw new BusinessConflictException("Only pending acceptance jobs can be assigned to a driver");
        }
        if (this.driver != null && !this.driver.getId().equals(driver.getId())) {
            throw new BusinessConflictException("This job is already assigned to another driver");
        }
        this.driver = driver;
    }

    public void unassignDriver() {
        if (status != LogisticsJobStatus.PENDING_ACCEPTANCE) {
            return;
        }
        this.driver = null;
    }

    public void submitProofOfDelivery(String storageKey, String fileName, String contentType, String deliveredTo) {
        if (status == LogisticsJobStatus.DELIVERED || status == LogisticsJobStatus.CANCELLED) {
            throw new BusinessConflictException("Proof of delivery cannot be submitted for a completed or cancelled job");
        }
        this.podStorageKey = storageKey;
        this.podFileName = fileName;
        this.podContentType = contentType;
        this.deliveredTo = deliveredTo;
        this.podSubmittedAt = OffsetDateTime.now();
    }
}