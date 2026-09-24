package com.kilivana.admin.service;

import com.kilivana.admin.api.AdminDashboardResponse;
import com.kilivana.admin.api.AdminReportsResponse;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.disputes.api.DisputeResponse;
import com.kilivana.disputes.domain.Dispute;
import com.kilivana.disputes.domain.DisputeStatus;
import com.kilivana.disputes.repository.DisputeRepository;
import com.kilivana.inspection.domain.InspectionStatus;
import com.kilivana.inspection.repository.InspectionRepository;
import com.kilivana.logistics.domain.LogisticsJob;
import com.kilivana.logistics.domain.LogisticsJobStatus;
import com.kilivana.logistics.api.LogisticsJobResponse;
import com.kilivana.logistics.repository.LogisticsJobRepository;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.orders.repository.OrderItemRepository;
import com.kilivana.orders.repository.OrderRepository;
import com.kilivana.payments.api.PaymentResponse;
import com.kilivana.payments.domain.Payment;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.payments.repository.PaymentRepository;
import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.service.ProductService;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.api.UserProfileResponse;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.DriverProfileRepository;
import com.kilivana.users.repository.UserRepository;
import com.kilivana.users.service.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class AdminDashboardService {

    private static final List<OrderStatus> ACTIVE_ORDER_STATUSES = List.of(
            OrderStatus.PLACED,
            OrderStatus.CONFIRMED,
            OrderStatus.READY_FOR_PICKUP,
            OrderStatus.IN_PROGRESS);

    private static final List<LogisticsJobStatus> ACTIVE_DELIVERY_STATUSES = List.of(
            LogisticsJobStatus.PENDING_ACCEPTANCE,
            LogisticsJobStatus.ACCEPTED,
            LogisticsJobStatus.AT_PICKUP,
            LogisticsJobStatus.PICKED_UP,
            LogisticsJobStatus.IN_TRANSIT);

    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final ProductService productService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final LogisticsJobRepository logisticsJobRepository;
    private final InspectionRepository inspectionRepository;
    private final DisputeRepository disputeRepository;
    private final UserService userService;

    public AdminDashboardService(
            CurrentUser currentUser,
            UserRepository userRepository,
            DriverProfileRepository driverProfileRepository,
            ProductService productService,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PaymentRepository paymentRepository,
            LogisticsJobRepository logisticsJobRepository,
            InspectionRepository inspectionRepository,
            DisputeRepository disputeRepository,
            UserService userService) {
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.productService = productService;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.logisticsJobRepository = logisticsJobRepository;
        this.inspectionRepository = inspectionRepository;
        this.disputeRepository = disputeRepository;
        this.userService = userService;
    }

    public AdminDashboardResponse dashboard() {
        requireAdmin();

        Map<UserRole, Long> usersByRole = new EnumMap<>(UserRole.class);
        for (UserRole role : UserRole.values()) {
            usersByRole.put(role, userRepository.countByRole(role));
        }

        Map<ProductStatus, Long> productsByStatus = new EnumMap<>(ProductStatus.class);
        for (ProductStatus status : ProductStatus.values()) {
            productsByStatus.put(status, productService.countByStatus(status));
        }

        Map<OrderStatus, Long> ordersByStatus = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            ordersByStatus.put(status, orderRepository.countByStatus(status));
        }

        Map<PaymentStatus, Long> paymentsByStatus = new EnumMap<>(PaymentStatus.class);
        for (PaymentStatus status : PaymentStatus.values()) {
            paymentsByStatus.put(status, paymentRepository.countByStatus(status));
        }

        Map<LogisticsJobStatus, Long> logisticsByStatus = new EnumMap<>(LogisticsJobStatus.class);
        for (LogisticsJobStatus status : LogisticsJobStatus.values()) {
            logisticsByStatus.put(status, logisticsJobRepository.countByStatus(status));
        }

        Map<InspectionStatus, Long> inspectionsByStatus = new EnumMap<>(InspectionStatus.class);
        inspectionsByStatus.put(InspectionStatus.SCHEDULED, inspectionRepository.countByStatus(InspectionStatus.SCHEDULED));
        inspectionsByStatus.put(InspectionStatus.IN_PROGRESS, inspectionRepository.countByStatus(InspectionStatus.IN_PROGRESS));
        inspectionsByStatus.put(InspectionStatus.PASSED, inspectionRepository.countByStatus(InspectionStatus.PASSED));
        inspectionsByStatus.put(InspectionStatus.FAILED, inspectionRepository.countByStatus(InspectionStatus.FAILED));

        Map<DisputeStatus, Long> disputesByStatus = new EnumMap<>(DisputeStatus.class);
        for (DisputeStatus status : DisputeStatus.values()) {
            disputesByStatus.put(status, disputeRepository.countByStatus(status));
        }

        long activeDeliveries = logisticsJobRepository.countByStatusIn(ACTIVE_DELIVERY_STATUSES);
        long pendingInspections = inspectionsByStatus.getOrDefault(InspectionStatus.SCHEDULED, 0L)
                + inspectionsByStatus.getOrDefault(InspectionStatus.IN_PROGRESS, 0L);
        long activeOrders = orderRepository.countByStatusNotIn(ACTIVE_ORDER_STATUSES) ;

        return new AdminDashboardResponse(
                new AdminDashboardResponse.UserCounts(
                        userRepository.count(),
                        userRepository.countByStatus(UserStatus.ACTIVE),
                        userRepository.countByStatus(UserStatus.SUSPENDED),
                        usersByRole,
                        driverProfileRepository.countByAvailability(com.kilivana.users.domain.DriverAvailability.AVAILABLE),
                        driverProfileRepository.countByAvailability(com.kilivana.users.domain.DriverAvailability.ON_JOB)),
                new AdminDashboardResponse.ProductCounts(
                        productsByStatus.values().stream().mapToLong(Long::longValue).sum(),
                        productsByStatus.getOrDefault(ProductStatus.ACTIVE, 0L),
                        productsByStatus.getOrDefault(ProductStatus.HIDDEN, 0L),
                        productsByStatus),
                new AdminDashboardResponse.OrderMetrics(
                        ordersByStatus.values().stream().mapToLong(Long::longValue).sum(),
                        ordersByStatus.getOrDefault(OrderStatus.COMPLETED, 0L),
                        activeOrders - ordersByStatus.getOrDefault(OrderStatus.COMPLETED, 0L) 
                                - ordersByStatus.getOrDefault(OrderStatus.CANCELLED, 0L)
                                - ordersByStatus.getOrDefault(OrderStatus.REJECTED, 0L),
                        ordersByStatus.getOrDefault(OrderStatus.CANCELLED, 0L),
                        orderRepository.sumTotalExcluding(OrderStatus.CANCELLED),
                        ordersByStatus),
                new AdminDashboardResponse.PaymentMetrics(
                        paymentsByStatus.values().stream().mapToLong(Long::longValue).sum(),
                        paymentsByStatus.getOrDefault(PaymentStatus.PENDING, 0L),
                        paymentsByStatus.getOrDefault(PaymentStatus.VERIFIED, 0L),
                        paymentsByStatus.getOrDefault(PaymentStatus.FAILED, 0L),
                        paymentsByStatus.getOrDefault(PaymentStatus.REFUNDED, 0L),
                        paymentsByStatus),
                new AdminDashboardResponse.LogisticsMetrics(
                        logisticsByStatus.values().stream().mapToLong(Long::longValue).sum(),
                        activeDeliveries,
                        logisticsByStatus.getOrDefault(LogisticsJobStatus.FAILED, 0L),
                        logisticsByStatus.getOrDefault(LogisticsJobStatus.PENDING_ACCEPTANCE, 0L),
                        logisticsByStatus),
                new AdminDashboardResponse.InspectionCounts(
                        inspectionsByStatus.values().stream().mapToLong(Long::longValue).sum(),
                        pendingInspections,
                        inspectionsByStatus.getOrDefault(InspectionStatus.PASSED, 0L),
                        inspectionsByStatus.getOrDefault(InspectionStatus.FAILED, 0L),
                        inspectionsByStatus),
                new AdminDashboardResponse.RatingCounts(
                        disputesByStatus.getOrDefault(DisputeStatus.OPEN, 0L),
                        disputesByStatus.getOrDefault(DisputeStatus.ESCALATED, 0L),
                        disputesByStatus.getOrDefault(DisputeStatus.RESOLVED, 0L),
                        disputesByStatus));
    }

    public Page<UserProfileResponse> users(UserRole role, UserStatus status, Pageable pageable) {
        requireAdmin();
        return userService.search(role, status, pageable);
    }

    public Page<com.kilivana.products.api.ProductResponse> products(
            ProductStatus status, Sector sector, UUID categoryId, UUID sellerId, String query, Pageable pageable) {
        return productService.adminBrowse(status, sector, categoryId, sellerId, query, pageable);
    }

    public Page<OrderResponse> orders(OrderStatus status, UUID buyerId, UUID sellerId, Pageable pageable) {
        requireAdmin();
        if (status != null && buyerId != null) {
            return orderRepository.findByStatusAndBuyerId(status, buyerId, pageable).map(o -> toOrderResponse(o));
        }
        if (status != null && sellerId != null) {
            return orderRepository.findByStatusAndSellerId(status, sellerId, pageable).map(o -> toOrderResponse(o));
        }
        if (buyerId != null) {
            return orderRepository.findByBuyerId(buyerId, pageable).map(o -> toOrderResponse(o));
        }
        if (sellerId != null) {
            return orderRepository.findBySellerId(sellerId, pageable).map(o -> toOrderResponse(o));
        }
        if (status != null) {
            return orderRepository.findByStatus(status, pageable).map(o -> toOrderResponse(o));
        }
        return orderRepository.findAllByOrderByCreatedAtDesc(pageable).map(o -> toOrderResponse(o));
    }

    public Page<LogisticsJobResponse> logistics(LogisticsJobStatus status, UUID driverId, Pageable pageable) {
        requireAdmin();
        if (driverId != null) {
            return logisticsJobRepository.findByDriverId(driverId, pageable).map(this::toJobResponse);
        }
        if (status != null) {
            return logisticsJobRepository.findByStatusIn(List.of(status), pageable).map(this::toJobResponse);
        }
        return logisticsJobRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toJobResponse);
    }

    public Page<PaymentResponse> payments(PaymentStatus status, String provider, Pageable pageable) {
        requireAdmin();
        if (status != null && provider != null && !provider.isBlank()) {
            return paymentRepository.findByStatusAndProvider(status, provider.trim(), pageable).map(this::toPaymentResponse);
        }
        if (status != null) {
            return paymentRepository.findByStatus(status, pageable).map(this::toPaymentResponse);
        }
        if (provider != null && !provider.isBlank()) {
            return paymentRepository.findByProvider(provider.trim(), pageable).map(this::toPaymentResponse);
        }
        return paymentRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toPaymentResponse);
    }

    public Page<DisputeResponse> disputes(DisputeStatus status, Pageable pageable) {
        requireAdmin();
        if (status != null) {
            return disputeRepository.findByStatus(status, pageable).map(this::toDisputeResponse);
        }
        return disputeRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toDisputeResponse);
    }

    public AdminReportsResponse reports() {
        requireAdmin();
        BigDecimal totalSales = orderRepository.sumTotalExcluding(OrderStatus.CANCELLED);
        long totalOrders = 0;
        for (OrderStatus status : OrderStatus.values()) {
            totalOrders += orderRepository.countByStatus(status);
        }
        long completed = orderRepository.countByStatus(OrderStatus.COMPLETED);

        Map<String, Long> last30Days = orderRepository.countByDaySince(OffsetDateTime.now().minusDays(30))
                .stream()
                .collect(Collectors.toMap(
                        row -> ((java.sql.Date) row[0]).toLocalDate().toString(),
                        row -> (Long) row[1],
                        Long::sum,
                        LinkedHashMap::new));

        BigDecimal avg = totalOrders == 0
                ? BigDecimal.ZERO
                : totalSales.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP);

        return new AdminReportsResponse(
                totalSales,
                totalOrders,
                completed,
                orderRepository.countByStatus(OrderStatus.CANCELLED),
                avg,
                userRepository.countByStatus(UserStatus.ACTIVE),
                logisticsJobRepository.countByStatusIn(ACTIVE_DELIVERY_STATUSES),
                inspectionRepository.countByStatusIn(List.of(InspectionStatus.SCHEDULED, InspectionStatus.IN_PROGRESS)),
                disputeRepository.countByStatus(DisputeStatus.OPEN),
                last30Days);
    }

    private OrderResponse toOrderResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerOrder().getId(),
                order.getBuyer().getId(),
                order.getSeller().getId(),
                order.getStatus(),
                order.getSubtotal(),
                order.getDeliveryFee(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getCreatedAt(),
                orderItemRepository.findByOrderId(order.getId()).stream()
                        .map(item -> new com.kilivana.orders.api.OrderItemResponse(
                                item.getProduct().getId(),
                                item.getProduct().getTitle(),
                                item.getQuantity(),
                                item.getUnitPrice(),
                                item.getLineTotal()))
                        .toList());
    }

    private LogisticsJobResponse toJobResponse(LogisticsJob job) {
        User driver = job.getDriver();
        return new LogisticsJobResponse(
                job.getId(),
                job.getOrder().getId(),
                job.getStatus(),
                driver == null ? null : driver.getId(),
                driver == null ? null : driver.getFullName(),
                job.getNotes(),
                job.getEstimatedArrival(),
                job.getPickupAddressId(),
                job.getDeliveryAddressId(),
                job.getPodSubmittedAt(),
                job.getDeliveredTo(),
                job.getCreatedAt());
    }

    private PaymentResponse toPaymentResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrder().getId(),
                payment.getStatus(),
                payment.getProvider(),
                payment.getAmount(),
                payment.getIdempotencyKey(),
                payment.getExternalId(),
                payment.getGatewayReference());
    }

    private DisputeResponse toDisputeResponse(Dispute dispute) {
        return new DisputeResponse(
                dispute.getId(),
                dispute.getOrder().getId(),
                dispute.getRaisedBy().getId(),
                dispute.getStatus(),
                dispute.getSubject(),
                dispute.getDescription(),
                dispute.getResolutionNote(),
                dispute.getResolvedBy() == null ? null : dispute.getResolvedBy().getId(),
                dispute.getResolvedAt(),
                dispute.getCreatedAt());
    }

    private void requireAdmin() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can access this endpoint");
        }
    }
}