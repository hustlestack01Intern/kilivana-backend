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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_order_id", nullable = false)
    private CustomerOrder customerOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryFee;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 20)
    private String currency;

    @OneToMany(mappedBy = "order")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(User buyer, User seller, CustomerOrder customerOrder,
                 OrderStatus status, BigDecimal totalAmount, String currency) {
        this(buyer, seller, customerOrder, status, totalAmount, BigDecimal.ZERO, currency);
    }

    public Order(User buyer, User seller, CustomerOrder customerOrder,
                 OrderStatus status, BigDecimal subtotal, BigDecimal deliveryFee, String currency) {
        this.buyer = buyer;
        this.seller = seller;
        this.customerOrder = customerOrder;
        this.status = status;
        this.subtotal = subtotal;
        this.deliveryFee = deliveryFee;
        this.totalAmount = subtotal.add(deliveryFee);
        this.currency = currency;
    }

    public User getBuyer() {
        return buyer;
    }

    public User getSeller() {
        return seller;
    }

    public CustomerOrder getCustomerOrder() {
        return customerOrder;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void transitionTo(OrderStatus nextStatus) {
        this.status = nextStatus;
    }
}
