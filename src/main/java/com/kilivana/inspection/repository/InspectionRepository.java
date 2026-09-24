package com.kilivana.inspection.repository;

import com.kilivana.inspection.domain.Inspection;
import com.kilivana.inspection.domain.InspectionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionRepository extends JpaRepository<Inspection, UUID> {

    @EntityGraph(attributePaths = {"inspector", "product", "product.owner"})
    Optional<Inspection> findWithActorsById(UUID id);

    @EntityGraph(attributePaths = {"inspector", "product"})
    List<Inspection> findByProductId(UUID productId);

    @EntityGraph(attributePaths = {"inspector", "product"})
    List<Inspection> findByInspectorId(UUID inspectorId);

    @EntityGraph(attributePaths = {"inspector", "product"})
    List<Inspection> findByProductOwnerId(UUID ownerId);

    long countByStatus(InspectionStatus status);

    long countByStatusIn(Collection<InspectionStatus> statuses);
}