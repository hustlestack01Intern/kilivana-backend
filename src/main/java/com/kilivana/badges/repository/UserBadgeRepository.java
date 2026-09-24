package com.kilivana.badges.repository;

import com.kilivana.badges.domain.UserBadge;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBadgeRepository extends JpaRepository<UserBadge, UUID> {

    @EntityGraph(attributePaths = {"user", "badge", "awardedBy"})
    List<UserBadge> findByUserId(UUID userId);

    boolean existsByUserIdAndBadgeId(UUID userId, UUID badgeId);
}