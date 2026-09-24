package com.kilivana.addresses.api;

import java.util.UUID;

public record AddressResponse(
        UUID addressId,
        String label,
        String addressLine,
        Double latitude,
        Double longitude,
        boolean isDefault) {
}