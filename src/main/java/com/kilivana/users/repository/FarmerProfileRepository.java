package com.kilivana.users.repository;

import com.kilivana.users.domain.FarmerProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FarmerProfileRepository extends JpaRepository<FarmerProfile, UUID> {

    Optional<FarmerProfile> findByUserId(UUID userId);
}