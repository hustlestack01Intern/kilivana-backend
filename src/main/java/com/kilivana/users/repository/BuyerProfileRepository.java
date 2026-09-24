package com.kilivana.users.repository;

import com.kilivana.users.domain.BuyerProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyerProfileRepository extends JpaRepository<BuyerProfile, UUID> {

    Optional<BuyerProfile> findByUserId(UUID userId);
}