package com.kilivana.users.repository;

import com.kilivana.users.domain.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "user")
    Optional<PasswordResetToken> findByToken(String token);

    List<PasswordResetToken> findByUserId(UUID userId);

    long deleteByExpiresAtBefore(OffsetDateTime expiresAt);
}
