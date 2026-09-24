package com.kilivana.products.repository;

import com.kilivana.products.domain.Product;
import com.kilivana.products.domain.ProductImage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    List<ProductImage> findByProductIdOrderBySortOrderAsc(UUID productId);

    void deleteByProduct(Product product);
}