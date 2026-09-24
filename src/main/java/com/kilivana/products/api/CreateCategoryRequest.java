package com.kilivana.products.api;

import com.kilivana.products.domain.Sector;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull Sector sector) {
}