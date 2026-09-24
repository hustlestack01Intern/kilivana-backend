package com.kilivana.badges.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.users.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_badges",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_badges_user_badge",
                columnNames = {"user_id", "badge_id"}))
public class UserBadge extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "badge_id", nullable = false)
    private Badge badge;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "awarded_by", nullable = false)
    private User awardedBy;

    @Column(nullable = false, length = 500)
    private String note;

    @Column(nullable = false)
    private OffsetDateTime awardedAt;

    protected UserBadge() {
    }

    public UserBadge(User user, Badge badge, User awardedBy, String note) {
        this.user = user;
        this.badge = badge;
        this.awardedBy = awardedBy;
        this.note = note;
        this.awardedAt = OffsetDateTime.now();
    }

    public User getUser() {
        return user;
    }

    public Badge getBadge() {
        return badge;
    }

    public User getAwardedBy() {
        return awardedBy;
    }

    public String getNote() {
        return note;
    }

    public OffsetDateTime getAwardedAt() {
        return awardedAt;
    }
}