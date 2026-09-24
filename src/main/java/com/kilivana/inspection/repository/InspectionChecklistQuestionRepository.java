package com.kilivana.inspection.repository;

import com.kilivana.inspection.domain.InspectionChecklistQuestion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionChecklistQuestionRepository extends JpaRepository<InspectionChecklistQuestion, UUID> {

    List<InspectionChecklistQuestion> findByChecklistIdOrderBySortOrderAsc(UUID checklistId);
}