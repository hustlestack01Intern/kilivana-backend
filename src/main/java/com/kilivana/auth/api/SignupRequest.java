package com.kilivana.auth.api;

import com.kilivana.users.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Size(max = 20) String phoneNumber,
        @Schema(implementation = String.class, allowableValues = {"BUYER", "FARMER", "SUPPLIER", "INSPECTOR"},
                description = "Public role. Inspector accounts enter PENDING verification and require Admin approval for Inspector privileges.")
        @NotNull UserRole role,
        @Size(max = 150) String businessName,
        @Size(max = 150) String farmName,
        @Size(max = 200) String farmLocation,
        @Schema(description = "Required and nonblank for INSPECTOR only; maximum 100 characters; trimmed before storage. No uniqueness or format requirement.")
        @Size(max = 100) String employeeCode,
        @Size(max = 100) String licenseNumber,
        @Size(max = 100) String vehicleType,
        @Size(max = 50) String vehiclePlate,
        @Size(max = 150) String serviceArea) {
}
