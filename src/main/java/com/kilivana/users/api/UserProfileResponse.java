package com.kilivana.users.api;

import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.domain.VerificationStatus;
import java.util.UUID;

public record UserProfileResponse(
        UUID userId,
        String email,
        String fullName,
        String phoneNumber,
        UserRole role,
        UserStatus status,
        VerificationStatus verificationStatus,
        String businessName,
        String farmName,
        String farmLocation,
        String employeeCode,
        String licenseNumber,
        String vehicleType,
        String vehiclePlate,
        String serviceArea) {
}