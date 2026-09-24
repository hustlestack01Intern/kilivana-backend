package com.kilivana.inspection.repository;

import com.kilivana.inspection.domain.VisitPhoto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitPhotoRepository extends JpaRepository<VisitPhoto, UUID> {

    @EntityGraph(attributePaths = {"inspection", "inspection.inspector", "inspection.product"})
    Optional<VisitPhoto> findWithDetailsById(UUID id);

    @EntityGraph(attributePaths = {"inspection", "inspection.inspector", "inspection.product"})
    List<VisitPhoto> findByInspectionId(UUID inspectionId);
}