package com.kilivana.auth.api;

import com.kilivana.users.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Size(max = 20) String phoneNumber,
        @NotNull UserRole role,
        @Size(max = 150) String businessName,
        @Size(max = 150) String farmName,
        @Size(max = 200) String farmLocation,
        @Size(max = 100) String employeeCode,
        @Size(max = 100) String licenseNumber,
        @Size(max = 100) String vehicleType,
        @Size(max = 50) String vehiclePlate,
        @Size(max = 150) String serviceArea) {
}