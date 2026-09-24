package com.kilivana.inspection.repository;

import com.kilivana.inspection.domain.InspectionChecklist;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionChecklistRepository extends JpaRepository<InspectionChecklist, UUID> {

    List<InspectionChecklist> findByActiveTrueOrderByNameAsc();
}