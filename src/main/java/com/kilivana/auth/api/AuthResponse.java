package com.kilivana.auth.api;

import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.VerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuthResponse(
        UUID userId,
        String email,
        String fullName,
        UserRole role,
        @Schema(description = "Authoritative verification state. Inspector privileges require VERIFIED; PENDING inspectors may authenticate.")
        VerificationStatus verificationStatus,
        String accessToken,
        String refreshToken,
        OffsetDateTime refreshTokenExpiresAt) {
}
