package com.kilivana.logistics.domain;

public enum LogisticsJobStatus {
    PENDING_ACCEPTANCE,
    ACCEPTED,
    AT_PICKUP,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    FAILED,
    CANCELLED
}