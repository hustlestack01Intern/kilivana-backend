package com.kilivana.logistics.repository;

import com.kilivana.logistics.domain.TrackingEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackingEventRepository extends JpaRepository<TrackingEvent, UUID> {

    @EntityGraph(attributePaths = "job")
    List<TrackingEvent> findByJobIdOrderByLoggedAtAsc(UUID jobId);
}