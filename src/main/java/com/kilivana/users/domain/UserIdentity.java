package com.kilivana.users.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_identities")
public class UserIdentity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 32)
    private String provider;

    /** Google's stable account identifier. An opaque string: documented as decimal today, never read as a number. */
    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "email_at_link_time", length = 120)
    private String emailAtLinkTime;

    @Column(name = "provider_email_verified", nullable = false)
    private boolean providerEmailVerified;

    @Column(name = "hosted_domain", length = 255)
    private String hostedDomain;

    @Column(name = "inspector_domain_approved", nullable = false)
    private boolean inspectorDomainApproved;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserIdentityStatus status;

    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt;

    @Column(name = "last_verified_at")
    private OffsetDateTime lastVerifiedAt;

    protected UserIdentity() {
    }

    public UserIdentity(
            User user,
            String provider,
            String subject,
            String emailAtLinkTime,
            boolean providerEmailVerified,
            String hostedDomain) {
        this.user = user;
        this.provider = provider;
        this.subject = subject;
        this.emailAtLinkTime = emailAtLinkTime;
        this.providerEmailVerified = providerEmailVerified;
        this.hostedDomain = hostedDomain;
        this.inspectorDomainApproved = false;
        this.status = UserIdentityStatus.ACTIVE;
        this.linkedAt = OffsetDateTime.now();
    }

    public User getUser() {
        return user;
    }

    public String getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmailAtLinkTime() {
        return emailAtLinkTime;
    }

    public boolean isProviderEmailVerified() {
        return providerEmailVerified;
    }

    public String getHostedDomain() {
        return hostedDomain;
    }

    public boolean isInspectorDomainApproved() {
        return inspectorDomainApproved;
    }

    public UserIdentityStatus getStatus() {
        return status;
    }

    public OffsetDateTime getLinkedAt() {
        return linkedAt;
    }

    public OffsetDateTime getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public boolean isActive() {
        return status == UserIdentityStatus.ACTIVE;
    }

    public void markVerified(OffsetDateTime verifiedAt) {
        this.lastVerifiedAt = verifiedAt;
    }

    /**
     * Records that the address came from a Workspace domain on the allowlist. Domain
     * membership proves the mailbox belongs to an organisation, never that the person holds
     * a role inside it, so this is a surfacing signal for an administrator and never an
     * approval.
     */
    public void approveInspectorDomain() {
        this.inspectorDomainApproved = true;
    }

    public void revoke() {
        this.status = UserIdentityStatus.REVOKED;
    }
}