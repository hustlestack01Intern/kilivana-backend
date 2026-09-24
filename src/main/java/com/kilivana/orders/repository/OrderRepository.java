package com.kilivana.orders.repository;

import com.kilivana.orders.domain.Order;
import com.kilivana.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = {"buyer", "seller"})
    Optional<Order> findWithActorsById(UUID id);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    List<Order> findByBuyerIdOrSellerId(UUID buyerId, UUID sellerId);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    List<Order> findByCustomerOrderId(UUID customerOrderId);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    List<Order> findByCustomerOrderIdAndStatus(UUID customerOrderId, OrderStatus status);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findByBuyerId(UUID buyerId, Pageable pageable);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findBySellerId(UUID sellerId, Pageable pageable);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findByStatusAndBuyerId(OrderStatus status, UUID buyerId, Pageable pageable);

    @EntityGraph(attributePaths = {"buyer", "seller"})
    Page<Order> findByStatusAndSellerId(OrderStatus status, UUID sellerId, Pageable pageable);

    long countByStatus(OrderStatus status);

    long countByBuyerId(UUID buyerId);

    long countBySellerId(UUID sellerId);

    long countByBuyerIdAndStatus(UUID buyerId, OrderStatus status);

    long countBySellerIdAndStatus(UUID sellerId, OrderStatus status);

    long countByStatusNotIn(List<OrderStatus> statuses);

    @Query("select coalesce(sum(order.totalAmount), 0) from Order order where order.status <> :cancelled")
    BigDecimal sumTotalExcluding(OrderStatus cancelled);

    @Query("select coalesce(sum(order.totalAmount), 0) from Order order where order.status <> :cancelled"
            + " and order.buyer.id = :buyerId")
    BigDecimal sumTotalExcludingForBuyer(UUID buyerId, OrderStatus cancelled);

    @Query("select coalesce(sum(order.totalAmount), 0) from Order order where order.status <> :cancelled"
            + " and order.seller.id = :sellerId")
    BigDecimal sumTotalExcludingForSeller(UUID sellerId, OrderStatus cancelled);

    @Query("""
            select function('date', order.createdAt), count(order)
            from Order order
            where order.createdAt >= :since
            group by function('date', order.createdAt)
            order by function('date', order.createdAt)
            """)
    List<Object[]> countByDaySince(OffsetDateTime since);
}
