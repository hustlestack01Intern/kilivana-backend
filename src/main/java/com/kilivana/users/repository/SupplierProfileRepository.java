package com.kilivana.users.repository;

import com.kilivana.users.domain.SupplierProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierProfileRepository extends JpaRepository<SupplierProfile, UUID> {

    Optional<SupplierProfile> findByUserId(UUID userId);
}