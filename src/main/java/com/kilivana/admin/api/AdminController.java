package com.kilivana.admin.api;

import com.kilivana.admin.service.AdminDashboardService;
import com.kilivana.disputes.api.DisputeResponse;
import com.kilivana.disputes.domain.DisputeStatus;
import com.kilivana.logistics.api.LogisticsJobResponse;
import com.kilivana.logistics.domain.LogisticsJobStatus;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.payments.api.PaymentResponse;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.products.api.ProductResponse;
import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.users.api.UserProfileResponse;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminDashboardService adminService;

    public AdminController(AdminDashboardService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<AdminDashboardResponse> dashboard() {
        return ResponseEntity.ok(adminService.dashboard());
    }

    @GetMapping("/users")
    public ResponseEntity<Page<UserProfileResponse>> users(
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(adminService.users(role, status, pageable));
    }

    @GetMapping("/products")
    public ResponseEntity<Page<ProductResponse>> products(
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) Sector sector,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) String query,
            Pageable pageable) {
        return ResponseEntity.ok(adminService.products(status, sector, categoryId, sellerId, query, pageable));
    }

    @GetMapping("/orders")
    public ResponseEntity<Page<OrderResponse>> orders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID buyerId,
            @RequestParam(required = false) UUID sellerId,
            Pageable pageable) {
        return ResponseEntity.ok(adminService.orders(status, buyerId, sellerId, pageable));
    }

    @GetMapping("/logistics/jobs")
    public ResponseEntity<Page<LogisticsJobResponse>> logistics(
            @RequestParam(required = false) LogisticsJobStatus status,
            @RequestParam(required = false) UUID driverId,
            Pageable pageable) {
        return ResponseEntity.ok(adminService.logistics(status, driverId, pageable));
    }

    @GetMapping("/payments")
    public ResponseEntity<Page<PaymentResponse>> payments(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String provider,
            Pageable pageable) {
        return ResponseEntity.ok(adminService.payments(status, provider, pageable));
    }

    @GetMapping("/disputes")
    public ResponseEntity<Page<DisputeResponse>> disputes(
            @RequestParam(required = false) DisputeStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(adminService.disputes(status, pageable));
    }

    @GetMapping("/reports")
    public ResponseEntity<AdminReportsResponse> reports() {
        return ResponseEntity.ok(adminService.reports());
    }
}