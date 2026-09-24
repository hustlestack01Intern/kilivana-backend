package com.kilivana.users.repository;

import com.kilivana.users.domain.InspectorProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectorProfileRepository extends JpaRepository<InspectorProfile, UUID> {

    Optional<InspectorProfile> findByUserId(UUID userId);
}