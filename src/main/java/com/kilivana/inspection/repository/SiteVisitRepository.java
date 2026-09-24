package com.kilivana.inspection.repository;

import com.kilivana.inspection.domain.SiteVisit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SiteVisitRepository extends JpaRepository<SiteVisit, UUID> {

    @EntityGraph(attributePaths = {"farmer", "product"})
    Optional<SiteVisit> findWithDetailsById(UUID id);

    @EntityGraph(attributePaths = {"farmer", "product"})
    List<SiteVisit> findByFarmerId(UUID farmerId);

    @Query("select visit from SiteVisit visit")
    @EntityGraph(attributePaths = {"farmer", "product"})
    List<SiteVisit> findAllWithDetails();
}