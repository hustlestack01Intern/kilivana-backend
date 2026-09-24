package com.kilivana.products.repository;

import com.kilivana.products.domain.SavedProduct;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedProductRepository extends JpaRepository<SavedProduct, UUID> {

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    @EntityGraph(attributePaths = {"product", "product.owner"})
    List<SavedProduct> findByUserIdOrderByCreatedAtDesc(UUID userId);

    void deleteByUserIdAndProductId(UUID userId, UUID productId);
}