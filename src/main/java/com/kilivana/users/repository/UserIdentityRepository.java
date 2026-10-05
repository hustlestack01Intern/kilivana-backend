package com.kilivana.users.repository;

import com.kilivana.users.domain.UserIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    /**
     * The only account-resolution query in the Google flow. Keyed on {@code sub} alone: the
     * email claim is attacker-influenced and is never a lookup key here.
     *
     * <p>{@code user} is fetched eagerly because {@code open-in-view} is off — every caller
     * of this method needs the user in the same transaction, and a LAZY proxy touched after
     * the transaction closes is the {@code LazyInitializationException} this repository's
     * configuration turns into a 500.
     */
    @EntityGraph(attributePaths = "user")
    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);

    boolean existsByProviderAndSubject(String provider, String subject);

    boolean existsByUserId(UUID userId);

    @EntityGraph(attributePaths = "user")
    List<UserIdentity> findAllByUserId(UUID userId);
}