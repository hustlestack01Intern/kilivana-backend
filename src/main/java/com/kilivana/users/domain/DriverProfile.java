package com.kilivana.users.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "driver_profiles")
public class DriverProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 100)
    private String licenseNumber;

    @Column(nullable = false, length = 100)
    private String vehicleType;

    @Column(nullable = false, length = 50)
    private String vehiclePlate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DriverAvailability availability;

    @Column(nullable = true, length = 150)
    private String serviceArea;

    protected DriverProfile() {
    }

    public DriverProfile(User user, String licenseNumber, String vehicleType, String vehiclePlate, String serviceArea) {
        this.user = user;
        this.licenseNumber = licenseNumber;
        this.vehicleType = vehicleType;
        this.vehiclePlate = vehiclePlate;
        this.availability = DriverAvailability.AVAILABLE;
        this.serviceArea = serviceArea;
    }

    public User getUser() {
        return user;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public String getVehiclePlate() {
        return vehiclePlate;
    }

    public DriverAvailability getAvailability() {
        return availability;
    }

    public String getServiceArea() {
        return serviceArea;
    }

    public void setAvailability(DriverAvailability availability) {
        this.availability = availability;
    }

    public void updateVehicle(String vehicleType, String vehiclePlate) {
        this.vehicleType = vehicleType;
        this.vehiclePlate = vehiclePlate;
    }

    public void updateServiceArea(String serviceArea) {
        this.serviceArea = serviceArea;
    }
}