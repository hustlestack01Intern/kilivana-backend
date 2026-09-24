package com.kilivana.addresses.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAddressRequest(
        @NotBlank @Size(max = 50) String label,
        @NotBlank @Size(max = 300) String addressLine,
        Double latitude,
        Double longitude,
        boolean isDefault) {
}