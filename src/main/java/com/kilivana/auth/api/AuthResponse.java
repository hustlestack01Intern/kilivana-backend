package com.kilivana.auth.api;

import com.kilivana.users.domain.UserRole;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuthResponse(
        UUID userId,
        String email,
        String fullName,
        UserRole role,
        String accessToken,
        String refreshToken,
        OffsetDateTime refreshTokenExpiresAt) {
}
