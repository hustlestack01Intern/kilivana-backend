package com.kilivana.users.api;

import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
        @Size(max = 100) String fullName,
        @Size(max = 20) String phoneNumber,
        @Size(max = 150) String businessName,
        @Size(max = 150) String farmName,
        @Size(max = 200) String farmLocation,
        @Size(max = 100) String vehicleType,
        @Size(max = 50) String vehiclePlate,
        @Size(max = 150) String serviceArea) {
}