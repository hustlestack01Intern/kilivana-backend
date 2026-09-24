package com.kilivana.disputes.repository;

import com.kilivana.disputes.domain.Dispute;
import com.kilivana.disputes.domain.DisputeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    @EntityGraph(attributePaths = {"order", "raisedBy", "resolvedBy"})
    Optional<Dispute> findWithActorsById(UUID id);

    Optional<Dispute> findByOrderIdAndStatusNot(UUID orderId, DisputeStatus status);

    @EntityGraph(attributePaths = {"order", "raisedBy", "resolvedBy"})
    List<Dispute> findByOrderSellerIdOrOrderBuyerIdOrRaisedById(UUID sellerId, UUID buyerId, UUID raisedById);

    @EntityGraph(attributePaths = {"order", "raisedBy", "resolvedBy"})
    Page<Dispute> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"order", "raisedBy", "resolvedBy"})
    Page<Dispute> findByStatus(DisputeStatus status, Pageable pageable);

    long countByStatus(DisputeStatus status);
}