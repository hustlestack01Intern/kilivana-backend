package com.kilivana.badges.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserBadgeResponse(
        UUID id,
        UUID userId,
        String badgeCode,
        String badgeName,
        String badgeDescription,
        String badgeIcon,
        String awardedBy,
        String note,
        OffsetDateTime awardedAt) {
}