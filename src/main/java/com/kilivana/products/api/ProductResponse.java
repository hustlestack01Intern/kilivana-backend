package com.kilivana.products.api;

import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.domain.SellerType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProductResponse(
        UUID productId,
        UUID sellerId,
        String sellerName,
        SellerType sellerType,
        Sector sector,
        ProductStatus status,
        UUID categoryId,
        String categoryName,
        String name,
        String description,
        String unit,
        BigDecimal unitPrice,
        BigDecimal availableQuantity,
        BigDecimal reservedQuantity,
        BigDecimal soldQuantity,
        BigDecimal lowStockThreshold,
        BigDecimal minimumOrderQuantity,
        boolean lowStock,
        List<ProductImageResponse> images,
        OffsetDateTime createdAt) {
}