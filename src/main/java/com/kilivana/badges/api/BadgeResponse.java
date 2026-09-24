package com.kilivana.badges.api;

import java.util.UUID;

public record BadgeResponse(
        UUID id,
        String code,
        String name,
        String description,
        String icon) {
}