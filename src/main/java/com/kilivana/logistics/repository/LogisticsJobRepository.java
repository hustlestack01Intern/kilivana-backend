package com.kilivana.logistics.repository;

import com.kilivana.logistics.domain.LogisticsJob;
import com.kilivana.logistics.domain.LogisticsJobStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsJobRepository extends JpaRepository<LogisticsJob, UUID> {

    @EntityGraph(attributePaths = {"order", "driver", "order.buyer", "order.seller"})
    Optional<LogisticsJob> findWithActorsById(UUID id);

    @EntityGraph(attributePaths = {"order", "driver", "order.buyer", "order.seller"})
    Optional<LogisticsJob> findByOrderId(UUID orderId);

    @EntityGraph(attributePaths = {"order", "driver"})
    List<LogisticsJob> findByOrderSellerIdOrOrderBuyerId(UUID sellerId, UUID buyerId);

    @EntityGraph(attributePaths = {"order", "driver"})
    List<LogisticsJob> findByDriverId(UUID driverId);

    @EntityGraph(attributePaths = {"order", "driver"})
    List<LogisticsJob> findByStatusOrderByCreatedAtAsc(LogisticsJobStatus status);

    @EntityGraph(attributePaths = {"order", "driver"})
    List<LogisticsJob> findByStatusAndDriverIsNullOrDriverId(LogisticsJobStatus status, UUID driverId);

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller", "driver"})
    Page<LogisticsJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller", "driver"})
    Page<LogisticsJob> findByStatus(LogisticsJobStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller", "driver"})
    Page<LogisticsJob> findByDriverId(UUID driverId, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.buyer", "order.seller", "driver"})
    Page<LogisticsJob> findByStatusIn(Collection<LogisticsJobStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"order", "driver"})
    List<LogisticsJob> findByStatus(LogisticsJobStatus status);

    long countByStatus(LogisticsJobStatus status);

    long countByStatusIn(Collection<LogisticsJobStatus> statuses);
}