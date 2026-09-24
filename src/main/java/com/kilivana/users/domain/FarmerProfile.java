package com.kilivana.users.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "farmer_profiles")
public class FarmerProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 150)
    private String farmName;

    @Column(nullable = false, length = 200)
    private String farmLocation;

    protected FarmerProfile() {
    }

    public FarmerProfile(User user, String farmName, String farmLocation) {
        this.user = user;
        this.farmName = farmName;
        this.farmLocation = farmLocation;
    }

    public User getUser() {
        return user;
    }

    public String getFarmName() {
        return farmName;
    }

    public String getFarmLocation() {
        return farmLocation;
    }

    public void updateFarm(String farmName, String farmLocation) {
        this.farmName = farmName;
        this.farmLocation = farmLocation;
    }
}
