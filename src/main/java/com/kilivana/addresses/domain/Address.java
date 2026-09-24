package com.kilivana.addresses.domain;

import com.kilivana.common.domain.BaseEntity;
import com.kilivana.users.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "addresses")
public class Address extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(nullable = false, length = 300)
    private String addressLine;

    @Column(nullable = true)
    private Double latitude;

    @Column(nullable = true)
    private Double longitude;

    @Column(nullable = false)
    private boolean isDefault;

    protected Address() {
    }

    public Address(User user, String label, String addressLine, Double latitude, Double longitude, boolean isDefault) {
        this.user = user;
        this.label = label;
        this.addressLine = addressLine;
        this.latitude = latitude;
        this.longitude = longitude;
        this.isDefault = isDefault;
    }

    public User getUser() {
        return user;
    }

    public String getLabel() {
        return label;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void update(String label, String addressLine, Double latitude, Double longitude, boolean isDefault) {
        this.label = label;
        this.addressLine = addressLine;
        this.latitude = latitude;
        this.longitude = longitude;
        this.isDefault = isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }
}