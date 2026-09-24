package com.kilivana.users.repository;

import com.kilivana.users.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "user")
    Optional<RefreshToken> findByTokenValue(String tokenValue);

    List<RefreshToken> findByFamilyId(UUID familyId);

    List<RefreshToken> findByUserId(UUID userId);

    long deleteByExpiresAtBefore(OffsetDateTime expiresAt);
}
