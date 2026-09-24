package com.kilivana.orders.repository;

import com.kilivana.orders.domain.CartItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    Optional<CartItem> findByBuyerIdAndProductId(UUID buyerId, UUID productId);

    @EntityGraph(attributePaths = {"product", "product.owner"})
    List<CartItem> findWithProductByBuyerId(UUID buyerId);
}