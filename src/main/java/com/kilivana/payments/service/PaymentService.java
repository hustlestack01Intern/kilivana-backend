package com.kilivana.payments.service;

import com.kilivana.common.event.PaymentStatusChangedEvent;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.repository.OrderRepository;
import com.kilivana.orders.service.OrderService;
import com.kilivana.paymentgateway.GatewayResponse;
import com.kilivana.paymentgateway.PaymentGateway;
import com.kilivana.paymentgateway.PaymentGatewayRegistry;
import com.kilivana.paymentgateway.PaymentIntent;
import com.kilivana.payments.api.CreatePaymentRequest;
import com.kilivana.payments.api.PaymentResponse;
import com.kilivana.payments.domain.Payment;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.payments.repository.PaymentRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.UserRole;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private static final Set<UserRole> SELLER_ROLES = Set.of(UserRole.SUPPLIER, UserRole.FARMER);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final CurrentUser currentUser;
    private final OrderService orderService;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            PaymentGatewayRegistry gatewayRegistry,
            CurrentUser currentUser,
            OrderService orderService,
            ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.gatewayRegistry = gatewayRegistry;
        this.currentUser = currentUser;
        this.orderService = orderService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public PaymentResponse create(CreatePaymentRequest request) {
        AuthenticatedUser actor = currentUser.required();
        Order order = orderRepository.findWithActorsById(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (!order.getBuyer().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Only the buyer side of an order can initiate payment");
        }

        String idempotencyKey = request.idempotencyKey().trim();
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    if (!existing.getOrder().getId().equals(order.getId())) {
                        throw new BusinessConflictException("Idempotency key is already used for a different payment");
                    }
                    return toResponse(existing);
                })
                .orElseGet(() -> {
                    Payment payment = new Payment(
                            order,
                            PaymentStatus.PENDING,
                            request.provider().trim(),
                            order.getTotalAmount(),
                            idempotencyKey);
                    PaymentGateway gateway = gatewayRegistry.forProvider(payment.getProvider());
                    String externalId = UUID.randomUUID().toString();
                    GatewayResponse response = gateway.pay(new PaymentIntent(
                            externalId,
                            idempotencyKey,
                            payment.getAmount(),
                            order.getCurrency()));
                    payment.recordGatewayDetails(externalId, response.reference());
                    paymentRepository.save(payment);
                    return toResponse(payment);
                });
    }

    @Transactional
    public PaymentResponse verify(String idempotencyKey) {
        AuthenticatedUser actor = currentUser.required();
        if (!SELLER_ROLES.contains(actor.getRole())) {
            throw new UnauthorizedOperationException("Only sellers can verify inbound payments");
        }
        Payment payment = paymentRepository.findByIdempotencyKey(idempotencyKey.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!payment.getOrder().getSeller().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only verify payments for your orders");
        }
        if (payment.getStatus() == PaymentStatus.VERIFIED) {
            return toResponse(payment);
        }
        PaymentStatus previous = payment.getStatus();
        payment.markVerified();
        publishPaymentEvent(payment, previous, PaymentStatus.VERIFIED);
        orderService.markCustomerOrderPaidWhenComplete(payment.getOrder().getId());
        return toResponse(payment);
    }

    @Transactional
    public PaymentResponse markFailed(UUID paymentId) {
        AuthenticatedUser actor = currentUser.required();
        Payment payment = requireSellerPayment(paymentId, actor);
        PaymentStatus previous = payment.getStatus();
        payment.markFailed();
        publishPaymentEvent(payment, previous, PaymentStatus.FAILED);
        return toResponse(payment);
    }

    @Transactional
    public PaymentResponse refund(UUID paymentId) {
        AuthenticatedUser actor = currentUser.required();
        Payment payment = requireSellerPayment(paymentId, actor);
        PaymentGateway gateway = gatewayRegistry.forProvider(payment.getProvider());
        gateway.refund(payment.getGatewayReference());
        PaymentStatus previous = payment.getStatus();
        payment.refund();
        publishPaymentEvent(payment, previous, PaymentStatus.REFUNDED);
        return toResponse(payment);
    }

    public List<PaymentResponse> myInboundPayments() {
        AuthenticatedUser actor = currentUser.required();
        if (!SELLER_ROLES.contains(actor.getRole())) {
            throw new UnauthorizedOperationException("Only sellers can track inbound payments");
        }
        return paymentRepository.findByOrderSellerId(actor.getId()).stream().map(this::toResponse).toList();
    }

    public List<PaymentResponse> myOutboundPayments() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.BUYER && actor.getRole() != UserRole.SUPPLIER) {
            throw new UnauthorizedOperationException("Only buyers can track outbound payments");
        }
        return paymentRepository.findByOrderBuyerId(actor.getId()).stream().map(this::toResponse).toList();
    }

    public PaymentResponse getById(UUID paymentId) {
        AuthenticatedUser actor = currentUser.required();
        Payment payment = paymentRepository.findWithOrderActorsById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        Order order = payment.getOrder();
        if (actor.getRole() != UserRole.ADMIN
                && !order.getBuyer().getId().equals(actor.getId())
                && !order.getSeller().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Only order participants or admins can view a payment");
        }
        return toResponse(payment);
    }

    private Payment requireSellerPayment(UUID paymentId, AuthenticatedUser actor) {
        if (!SELLER_ROLES.contains(actor.getRole())) {
            throw new UnauthorizedOperationException("Only sellers can manage inbound payments");
        }
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!payment.getOrder().getSeller().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only manage payments for your orders");
        }
        return payment;
    }

    private void publishPaymentEvent(Payment payment, PaymentStatus previousStatus, PaymentStatus newStatus) {
        eventPublisher.publishEvent(new PaymentStatusChangedEvent(
                payment.getId(),
                payment.getOrder().getId(),
                previousStatus,
                newStatus,
                OffsetDateTime.now()));
    }

    private PaymentResponse toResponse(Payment payment) {
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
}
