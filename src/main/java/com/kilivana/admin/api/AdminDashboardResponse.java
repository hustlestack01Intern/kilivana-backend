package com.kilivana.admin.api;

import com.kilivana.disputes.domain.DisputeStatus;
import com.kilivana.inspection.domain.InspectionStatus;
import com.kilivana.logistics.domain.LogisticsJobStatus;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.products.domain.ProductStatus;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AdminDashboardResponse(
        UserCounts users,
        ProductCounts products,
        OrderMetrics orders,
        PaymentMetrics payments,
        LogisticsMetrics logistics,
        InspectionCounts inspections,
        RatingCounts disputes) {

    public record UserCounts(
            long totalUsers,
            long activeUsers,
            long suspendedUsers,
            Map<UserRole, Long> byRole,
            long availableDrivers,
            long onJobDrivers) {
    }

    public record ProductCounts(
            long totalProducts,
            long activeProducts,
            long hiddenProducts,
            Map<ProductStatus, Long> byStatus) {
    }

    public record OrderMetrics(
            long totalOrders,
            long completedOrders,
            long activeOrders,
            long cancelledOrders,
            BigDecimal salesTotal,
            Map<OrderStatus, Long> byStatus) {
    }

    public record PaymentMetrics(
            long totalPayments,
            long pendingPayments,
            long verifiedPayments,
            long failedPayments,
            long refundedPayments,
            Map<PaymentStatus, Long> byStatus) {
    }

    public record LogisticsMetrics(
            long totalJobs,
            long activeDeliveries,
            long failedDeliveries,
            long pendingAcceptance,
            Map<LogisticsJobStatus, Long> byStatus) {
    }

    public record InspectionCounts(
            long totalInspections,
            long pendingInspections,
            long passedInspections,
            long failedInspections,
            Map<InspectionStatus, Long> byStatus) {
    }

    public record RatingCounts(
            long openDisputes,
            long escalatedDisputes,
            long resolvedDisputes,
            Map<DisputeStatus, Long> byStatus) {
    }
}