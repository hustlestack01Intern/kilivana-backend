package com.kilivana.users.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "supplier_profiles")
public class SupplierProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 150)
    private String businessName;

    protected SupplierProfile() {
    }

    public SupplierProfile(User user, String businessName) {
        this.user = user;
        this.businessName = businessName;
    }

    public User getUser() {
        return user;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void updateBusinessName(String businessName) {
        this.businessName = businessName;
    }
}
