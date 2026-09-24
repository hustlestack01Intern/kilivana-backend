package com.kilivana.admin.api;

import java.math.BigDecimal;
import java.util.Map;

public record AdminReportsResponse(
        BigDecimal totalSales,
        long totalOrders,
        long completedOrders,
        long cancelledOrders,
        BigDecimal averageOrderValue,
        long activeUsers,
        long activeDeliveries,
        long pendingInspections,
        long openDisputes,
        Map<String, Long> ordersLast30Days) {
}