package com.kilivana.users.api;

import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.VerificationStatus;
import java.util.UUID;

public record PublicSellerProfileResponse(
        UUID sellerId,
        String displayName,
        UserRole role,
        VerificationStatus verificationStatus,
        String businessName,
        String farmName,
        String farmLocation) {
}
