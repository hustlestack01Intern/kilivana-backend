package com.kilivana.users.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Proof that somebody controls a mailbox, held before any account exists for it.
 *
 * <p>Deliberately carries no reference to a user: the guarantee this table records is "this
 * address was proven", and proving an address must be possible precisely when there is no
 * account to attach it to. The absence of a foreign key is the design, not an omission.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken extends BaseEntity {

    /** SHA-256 hex of the token actually mailed. The raw value is never stored. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Already normalised: {@code trim().toLowerCase(Locale.ROOT)}, matching {@code AuthService.registerUser}. */
    @Column(nullable = false, length = 120)
    private String email;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    protected EmailVerificationToken() {
    }

    public EmailVerificationToken(String tokenHash, String email, OffsetDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.email = email;
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getEmail() {
        return email;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public boolean isSpent() {
        return usedAt != null;
    }

    public boolean isExpired(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void markUsed(OffsetDateTime when) {
        this.usedAt = when;
    }
}