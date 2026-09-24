package com.kilivana.orders.repository;

import com.kilivana.orders.domain.OrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    @EntityGraph(attributePaths = "product")
    List<OrderItem> findByOrderId(UUID orderId);
}
