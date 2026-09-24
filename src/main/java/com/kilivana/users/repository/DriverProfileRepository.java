package com.kilivana.users.repository;

import com.kilivana.users.domain.DriverAvailability;
import com.kilivana.users.domain.DriverProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverProfileRepository extends JpaRepository<DriverProfile, UUID> {

    Optional<DriverProfile> findByUserId(UUID userId);

    List<DriverProfile> findByAvailability(DriverAvailability availability);

    long countByAvailability(DriverAvailability availability);
}