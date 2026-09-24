package com.kilivana.domain;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.products.domain.Product;
import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.domain.SellerType;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    private Product product() {
        User owner = new User(
                "owner@example.com",
                "hash",
                "Owner",
                "+254700000001",
                UserRole.FARMER,
                UserStatus.ACTIVE);
        return new Product(
                owner,
                SellerType.FARMER,
                Sector.FARM_PRODUCE,
                ProductStatus.ACTIVE,
                null,
                "Maize",
                "Dry maize",
                "kg",
                new BigDecimal("42.50"),
                new BigDecimal("100.00"),
                null,
                new BigDecimal("1.00"));
    }

    @Test
    void reserveShouldReduceAvailableQuantity() {
        Product product = product();

        product.reserveQuantity(new BigDecimal("25.00"));

        assertThat(product.getAvailableQuantity()).isEqualByComparingTo("75.00");
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void reserveFullQuantityShouldDeactivateProduct() {
        Product product = product();

        product.reserveQuantity(new BigDecimal("100.00"));

        assertThat(product.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(product.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }

    @Test
    void reserveBeyondStockShouldReject() {
        Product product = product();

        assertThatThrownBy(() -> product.reserveQuantity(new BigDecimal("150.00")))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("exceeds available stock");
    }

    @Test
    void reserveOnInactiveProductShouldReject() {
        Product product = product();
        product.reserveQuantity(new BigDecimal("100.00"));

        assertThatThrownBy(() -> product.reserveQuantity(new BigDecimal("1.00")))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void releaseShouldRestoreQuantityAndReactivate() {
        Product product = product();
        product.reserveQuantity(new BigDecimal("100.00"));

        product.releaseQuantity(new BigDecimal("40.00"));

        assertThat(product.getAvailableQuantity()).isEqualByComparingTo("40.00");
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void updateShouldDeactivateWhenQuantityReachesZero() {
        Product product = product();

        product.update("Maize", "Updated", "kg", new BigDecimal("50.00"), new BigDecimal("0.00"),
                null, null, new BigDecimal("1.00"));

        assertThat(product.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }
}