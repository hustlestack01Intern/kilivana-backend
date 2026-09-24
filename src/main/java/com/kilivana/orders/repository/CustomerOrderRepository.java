package com.kilivana.orders.repository;

import com.kilivana.orders.domain.CustomerOrder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

    @EntityGraph(attributePaths = "buyer")
    Optional<CustomerOrder> findWithBuyerById(UUID id);
}