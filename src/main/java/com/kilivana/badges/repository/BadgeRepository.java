package com.kilivana.badges.repository;

import com.kilivana.badges.domain.Badge;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BadgeRepository extends JpaRepository<Badge, UUID> {

    Optional<Badge> findByCode(String code);
}