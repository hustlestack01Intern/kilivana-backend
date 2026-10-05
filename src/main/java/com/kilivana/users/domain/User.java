package com.kilivana.users.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.users.service.AccountIdentity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(nullable = false, unique = true, length = 30)
    private String username;

    /**
     * Free-text county/region of operation, owned by the account rather than by the farmer
     * profile. Nullable: a buyer or an admin has no farm to locate. V11 constrains it only
     * when present, so a null here is a schema fact rather than a placeholder.
     */
    @Column(length = 200)
    private String region;

    /**
     * Null for an account that has no password, which today means a Google-only account.
     * An empty string is not used as a sentinel: it is a value bcrypt would still be asked
     * to match, which turns a schema fact into a runtime surprise. V16 drops the V1 NOT NULL.
     */
    @Column(length = 120)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, unique = true, length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationStatus verificationStatus;

    /**
     * When the address of record was proven to belong to this account's owner. Nullable and
     * never backfilled — see V16. Deliberately distinct from {@code verificationStatus},
     * which is the Inspector approval state that {@code AuthenticatedUser} turns into
     * authorities.
     */
    @Column(name = "email_verified_at")
    private OffsetDateTime emailVerifiedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<RefreshToken> refreshTokens = new HashSet<>();

    protected User() {
    }

    /**
     * Every constructor leaves {@code username} populated with a generated, regex-valid value,
     * because V11 makes the column NOT NULL and a signup that sets a chosen username must
     * therefore *overwrite* this one before persisting rather than rely on the field starting
     * empty. Generating rather than leaving null means a constructor caller that forgets cannot
     * reach the database with a null username and fail at the INSERT with a bare constraint
     * name.
     */
    public User(
            String email,
            String passwordHash,
            String fullName,
            String phoneNumber,
            UserRole role,
            UserStatus status) {
        this.email = email;
        this.username = AccountIdentity.generatedUsername();
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.role = role;
        this.status = status;
        this.verificationStatus = VerificationStatus.UNVERIFIED;
    }

    public String getUsername() { return username; }

    public void setUsername(String username) { this.username = username; }

    public String getRegion() { return region; }

    public void setRegion(String region) { this.region = region; }

    /**
     * A second constructor rather than a nullable argument on the first, so "this account has
     * no password" is a distinct call site and cannot be reached by passing null past a
     * password encoder that would have thrown.
     */
    public User(
            String email,
            String fullName,
            String phoneNumber,
            UserRole role,
            UserStatus status) {
        this(email, null, fullName, phoneNumber, role, status);
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public OffsetDateTime getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    /**
     * Whether the address of record has been proven. A named method rather than a
     * {@code getEmailVerifiedAt() != null} at each call site, because the null/present
     * distinction is the whole meaning of the column and a bare null check is what would
     * let it drift.
     */
    public boolean hasProvenEmail() {
        return emailVerifiedAt != null;
    }

    public void proveEmail(OffsetDateTime provenAt) {
        this.emailVerifiedAt = provenAt;
    }

    public void markVerification(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public void updatePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Set<RefreshToken> getRefreshTokens() {
        return refreshTokens;
    }

    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    public void activate() {
        this.status = UserStatus.ACTIVE;
    }
}
