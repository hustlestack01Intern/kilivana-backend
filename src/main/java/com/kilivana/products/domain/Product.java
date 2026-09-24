package com.kilivana.products.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.users.domain.User;
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
@Table(name = "products")
public class Product extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SellerType sellerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Sector sector;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false, length = 30)
    private String unit;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal availableQuantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal reservedQuantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal soldQuantity;

    @Column(nullable = true, precision = 12, scale = 2)
    private BigDecimal lowStockThreshold;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal minimumOrderQuantity;

    protected Product() {
    }

    public Product(
            User owner,
            SellerType sellerType,
            Sector sector,
            ProductStatus status,
            Category category,
            String title,
            String description,
            String unit,
            BigDecimal unitPrice,
            BigDecimal availableQuantity,
            BigDecimal lowStockThreshold,
            BigDecimal minimumOrderQuantity) {
        this.owner = owner;
        this.sellerType = sellerType;
        this.sector = sector;
        this.status = status;
        this.category = category;
        this.title = title;
        this.description = description;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = BigDecimal.ZERO;
        this.soldQuantity = BigDecimal.ZERO;
        this.lowStockThreshold = lowStockThreshold;
        this.minimumOrderQuantity = minimumOrderQuantity;
    }

    public User getOwner() {
        return owner;
    }

    public SellerType getSellerType() {
        return sellerType;
    }

    public Sector getSector() {
        return sector;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public Category getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getAvailableQuantity() {
        return availableQuantity;
    }

    public BigDecimal getReservedQuantity() {
        return reservedQuantity;
    }

    public BigDecimal getSoldQuantity() {
        return soldQuantity;
    }

    public BigDecimal getLowStockThreshold() {
        return lowStockThreshold;
    }

    public BigDecimal getMinimumOrderQuantity() {
        return minimumOrderQuantity;
    }

    public boolean isLowStock() {
        return lowStockThreshold != null && availableQuantity.compareTo(lowStockThreshold) <= 0;
    }

    public void update(
            String title,
            String description,
            String unit,
            BigDecimal unitPrice,
            BigDecimal availableQuantity,
            Category category,
            BigDecimal lowStockThreshold,
            BigDecimal minimumOrderQuantity) {
        this.title = title;
        this.description = description;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.availableQuantity = availableQuantity;
        this.category = category;
        this.lowStockThreshold = lowStockThreshold;
        this.minimumOrderQuantity = minimumOrderQuantity;
        if (this.status == ProductStatus.ACTIVE && availableQuantity.signum() == 0) {
            this.status = ProductStatus.INACTIVE;
        }
    }

    public void reserveQuantity(BigDecimal quantity) {
        if (status != ProductStatus.ACTIVE) {
            throw new BusinessConflictException("Product is not active");
        }
        if (availableQuantity.compareTo(quantity) < 0) {
            throw new BusinessConflictException("Requested quantity exceeds available stock");
        }
        availableQuantity = availableQuantity.subtract(quantity);
        reservedQuantity = reservedQuantity.add(quantity);
        if (availableQuantity.signum() == 0) {
            status = ProductStatus.INACTIVE;
        }
    }

    public void releaseQuantity(BigDecimal quantity) {
        availableQuantity = availableQuantity.add(quantity);
        reservedQuantity = reservedQuantity.subtract(quantity);
        if (availableQuantity.signum() > 0 && status == ProductStatus.INACTIVE) {
            status = ProductStatus.ACTIVE;
        }
    }

    public void markSold(BigDecimal quantity) {
        reservedQuantity = reservedQuantity.subtract(quantity);
        soldQuantity = soldQuantity.add(quantity);
    }

    public void activate() {
        this.status = ProductStatus.ACTIVE;
    }

    public void hide() {
        this.status = ProductStatus.HIDDEN;
    }

    public void markPendingApproval() {
        this.status = ProductStatus.PENDING_APPROVAL;
    }
}