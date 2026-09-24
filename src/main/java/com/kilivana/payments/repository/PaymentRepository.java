package com.kilivana.payments.repository;

import com.kilivana.payments.domain.Payment;
import com.kilivana.payments.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller"})
    Optional<Payment> findWithOrderActorsById(UUID id);

    @EntityGraph(attributePaths = "order")
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = "order")
    List<Payment> findByOrderId(UUID orderId);

    @EntityGraph(attributePaths = "order")
    List<Payment> findByOrderSellerId(UUID sellerId);

    @EntityGraph(attributePaths = "order")
    List<Payment> findByOrderBuyerId(UUID buyerId);

    @EntityGraph(attributePaths = "order")
    Optional<Payment> findByExternalId(String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from Payment payment join fetch payment.order where payment.externalId = :externalId")
    Optional<Payment> findByExternalIdForUpdate(String externalId);

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller"})
    Page<Payment> findByProvider(String provider, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller"})
    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller"})
    Page<Payment> findByStatusAndProvider(PaymentStatus status, String provider, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller"})
    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(PaymentStatus status);

    long countByProvider(String provider);
}
