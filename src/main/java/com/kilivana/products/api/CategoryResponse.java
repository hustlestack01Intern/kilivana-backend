package com.kilivana.products.api;

import com.kilivana.products.domain.Sector;
import java.util.UUID;

public record CategoryResponse(
        UUID categoryId,
        String name,
        Sector sector,
        boolean active) {
}