package com.kilivana.payments.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.orders.domain.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = true, unique = true, length = 150)
    private String externalId;

    @Column(nullable = true, length = 150)
    private String gatewayReference;

    protected Payment() {
    }

    public Payment(Order order, PaymentStatus status, String provider, BigDecimal amount, String idempotencyKey) {
        this.order = order;
        this.status = status;
        this.provider = provider;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
    }

    public void recordGatewayDetails(String externalId, String gatewayReference) {
        this.externalId = externalId;
        this.gatewayReference = gatewayReference;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public Order getOrder() {
        return order;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void markVerified() {
        if (status != PaymentStatus.PENDING) {
            throw new BusinessConflictException("Only pending payments can be verified");
        }
        this.status = PaymentStatus.VERIFIED;
    }

    public void markFailed() {
        if (status != PaymentStatus.PENDING) {
            throw new BusinessConflictException("Only pending payments can be marked as failed");
        }
        this.status = PaymentStatus.FAILED;
    }

    public void refund() {
        if (status != PaymentStatus.VERIFIED) {
            throw new BusinessConflictException("Only verified payments can be refunded");
        }
        this.status = PaymentStatus.REFUNDED;
    }
}
