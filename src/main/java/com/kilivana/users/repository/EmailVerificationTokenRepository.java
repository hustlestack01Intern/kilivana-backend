package com.kilivana.users.repository;

import com.kilivana.users.domain.EmailVerificationToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * Spends the token with a conditional update rather than an entity write.
     *
     * <p>Two concurrent redemptions of one token must produce exactly one success. An entity
     * write would let both load the row as unspent and both save it, leaving the {@code version}
     * column to pick the winner and the loser to surface as a 409 rather than a 400 — so this
     * is a single statement that reports how many rows it actually changed. Zero changed rows
     * means somebody else got there first, which is the same answer as an expired or unknown
     * token.
     *
     * <p>A native statement because a bulk JPQL update bypasses the {@code @PreUpdate} callback
     * and would leave {@code updated_at} claiming the row had never moved.
     */
    @Modifying
    @Query(value = """
            update email_verification_tokens
               set used_at = :now, updated_at = :now
             where id = :id and used_at is null
            """, nativeQuery = true)
    int spend(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}