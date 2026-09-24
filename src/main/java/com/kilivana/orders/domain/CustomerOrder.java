package com.kilivana.orders.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.users.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "customer_orders")
public class CustomerOrder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerOrderStatus status;

    @Column(nullable = false, length = 20)
    private String currency;

    @Column(nullable = true)
    private UUID shippingAddressId;

    @Column(nullable = true)
    private UUID pickupAddressId;

    protected CustomerOrder() {
    }

    public CustomerOrder(User buyer, String currency, UUID shippingAddressId, UUID pickupAddressId) {
        this.buyer = buyer;
        this.status = CustomerOrderStatus.NEW;
        this.currency = currency;
        this.shippingAddressId = shippingAddressId;
        this.pickupAddressId = pickupAddressId;
    }

    public User getBuyer() {
        return buyer;
    }

    public CustomerOrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getShippingAddressId() {
        return shippingAddressId;
    }

    public UUID getPickupAddressId() {
        return pickupAddressId;
    }

    public void markPaid() {
        this.status = CustomerOrderStatus.PAID;
    }

    public void cancel() {
        this.status = CustomerOrderStatus.CANCELLED;
    }

    public boolean isCancellable() {
        return status == CustomerOrderStatus.NEW;
    }
}