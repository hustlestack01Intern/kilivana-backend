package com.kilivana.orders.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.products.domain.Product;
import com.kilivana.users.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "cart_items")
public class CartItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    protected CartItem() {
    }

    public CartItem(User buyer, Product product, BigDecimal quantity) {
        this.buyer = buyer;
        this.product = product;
        this.quantity = quantity;
    }

    public User getBuyer() {
        return buyer;
    }

    public Product getProduct() {
        return product;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void addQuantity(BigDecimal additional) {
        this.quantity = this.quantity.add(additional);
    }

    public void updateQuantity(BigDecimal newQuantity) {
        this.quantity = newQuantity;
    }
}