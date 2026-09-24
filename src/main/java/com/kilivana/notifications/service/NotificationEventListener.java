package com.kilivana.notifications.service;

import com.kilivana.common.event.ContactMessageReceivedEvent;
import com.kilivana.common.event.InspectionCompletedEvent;
import com.kilivana.common.event.LogisticsJobStatusChangedEvent;
import com.kilivana.common.event.OrderStatusChangedEvent;
import com.kilivana.common.event.PaymentStatusChangedEvent;
import com.kilivana.notifications.domain.NotificationType;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.orders.repository.OrderRepository;
import com.kilivana.products.domain.Product;
import com.kilivana.products.repository.ProductRepository;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.repository.UserRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public NotificationEventListener(
            NotificationService notificationService,
            OrderRepository orderRepository,
            ProductRepository productRepository,
            UserRepository userRepository) {
        this.notificationService = notificationService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        Order order = orderRepository.findWithActorsById(event.orderId()).orElse(null);
        if (order == null) {
            return;
        }
        String title = "Order " + event.newStatus().name().replace('_', ' ').toLowerCase();
        String body = "Order #" + shortId(order.getId()) + " is now " + event.newStatus().name();
        notificationService.create(order.getBuyer().getId(), NotificationType.ORDER, title, body,
                "/orders/" + order.getId());
        if (event.newStatus() == OrderStatus.CONFIRMED || event.newStatus() == OrderStatus.REJECTED
                || event.newStatus() == OrderStatus.DELIVERED || event.newStatus() == OrderStatus.COMPLETED) {
            notificationService.create(order.getSeller().getId(), NotificationType.ORDER, title, body,
                    "/orders/" + order.getId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {
        if (event.newStatus() == null) {
            return;
        }
        String verb = switch (event.newStatus().name()) {
            case "VERIFIED" -> "Payment confirmed";
            case "REFUNDED" -> "Refund issued";
            case "FAILED" -> "Payment failed";
            default -> "Payment " + event.newStatus().name();
        };
        Order order = orderRepository.findWithActorsById(event.orderId()).orElse(null);
        if (order != null) {
            notificationService.create(order.getBuyer().getId(), NotificationType.PAYMENT, verb,
                    verb + " for order #" + shortId(order.getId()), "/payments/" + event.paymentId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInspectionCompleted(InspectionCompletedEvent event) {
        Product product = productRepository.findById(event.listingId()).orElse(null);
        if (product == null) {
            return;
        }
        notificationService.create(product.getOwner().getId(), NotificationType.INSPECTION,
                event.passed() ? "Inspection passed" : "Inspection failed",
                "The inspection for '" + product.getTitle() + "' is now "
                        + (event.passed() ? "passed" : "failed"),
                "/inspections/" + event.inspectionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLogisticsJobStatusChanged(LogisticsJobStatusChangedEvent event) {
        Order order = orderRepository.findWithActorsById(event.orderId()).orElse(null);
        if (order == null) {
            return;
        }
        String title = "Delivery " + event.newStatus().name().replace('_', ' ').toLowerCase();
        String body = "Delivery for order #" + shortId(order.getId()) + " is now "
                + event.newStatus().name();
        notificationService.create(order.getBuyer().getId(), NotificationType.LOGISTICS, title, body,
                "/logistics/jobs/" + event.jobId());
        notificationService.create(order.getSeller().getId(), NotificationType.LOGISTICS, title, body,
                "/logistics/jobs/" + event.jobId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContactMessageReceived(ContactMessageReceivedEvent event) {
        for (User admin : userRepository.findAllByRole(UserRole.ADMIN)) {
            notificationService.create(admin.getId(), NotificationType.MESSAGE,
                    "New contact message",
                    "A new message '" + event.subject() + "' needs attention",
                    "/admin/contact-messages/" + event.messageId());
        }
    }

    private String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}