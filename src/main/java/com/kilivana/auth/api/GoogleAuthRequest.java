package com.kilivana.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleAuthRequest(
        @Schema(description = "The credential JWT Google returns from the Android/iOS sign-in flow. "
                + "Verified against Google's published keys; never logged.")
        @NotBlank @Size(max = 4096) String idToken) {
}