package com.kilivana.users.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 150)
    private String tokenValue;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    protected RefreshToken() {
    }

    public RefreshToken(User user, String tokenValue, OffsetDateTime expiresAt) {
        this(user, tokenValue, expiresAt, UUID.randomUUID());
    }

    public RefreshToken(User user, String tokenValue, OffsetDateTime expiresAt, UUID familyId) {
        this.user = user;
        this.tokenValue = tokenValue;
        this.familyId = familyId == null ? UUID.randomUUID() : familyId;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    public User getUser() {
        return user;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public boolean isExpired(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void revoke() {
        this.revoked = true;
    }
}
