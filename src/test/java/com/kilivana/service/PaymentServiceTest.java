package com.kilivana.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.orders.domain.CustomerOrder;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.orders.repository.OrderRepository;
import com.kilivana.orders.service.OrderService;
import com.kilivana.paymentgateway.GatewayResponse;
import com.kilivana.paymentgateway.GatewayStatus;
import com.kilivana.paymentgateway.PaymentGateway;
import com.kilivana.paymentgateway.PaymentGatewayRegistry;
import com.kilivana.paymentgateway.PaymentIntent;
import com.kilivana.paymentgateway.SimulatedPaymentGateway;
import com.kilivana.payments.domain.Payment;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.payments.repository.PaymentRepository;
import com.kilivana.payments.service.PaymentService;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentGatewayRegistry gatewayRegistry;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private OrderService orderService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PaymentGateway authoritativeGateway;

    /**
     * A gateway that claims to be non-authoritative but performs a refund perfectly
     * competently if asked. Its only purpose is to make the service's own check load-bearing.
     */
    @Mock
    private PaymentGateway nonAuthoritativeButCompliantGateway;

    private PaymentService paymentService;

    private final UUID sellerId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    private Payment payment;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository,
                orderRepository,
                gatewayRegistry,
                currentUser,
                orderService,
                eventPublisher);

        User buyer = new User("buyer@example.com", "hash", "Buyer", "+254700000008",
                UserRole.BUYER, UserStatus.ACTIVE);
        User seller = new User("seller@example.com", "hash", "Seller", "+254700000009",
                UserRole.FARMER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(seller, "id", sellerId);
        Order order = new Order(buyer, seller, new CustomerOrder(buyer, "KES", null, null),
                OrderStatus.CONFIRMED, new BigDecimal("100.00"), "KES");

        payment = new Payment(order, PaymentStatus.PENDING, "MPESA", new BigDecimal("100.00"), "idem-key-1");
        payment.recordGatewayDetails("external-1", "MPESA-reference-1");
        payment.markVerified();
        ReflectionTestUtils.setField(payment, "id", paymentId);
    }

    private void stubSellerAccess() {
        when(currentUser.required()).thenReturn(
                new AuthenticatedUser(sellerId, "seller@example.com", "hash", UserRole.FARMER));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    }

    @Test
    void refundIsRefusedWhileTheGatewayIsSimulated() {
        stubSellerAccess();
        SimulatedPaymentGateway simulated = new SimulatedPaymentGateway();
        when(gatewayRegistry.forProvider("MPESA")).thenReturn(simulated);

        assertThatThrownBy(() -> paymentService.refund(paymentId))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("simulated");

        // The invariant: REFUNDED is only ever written on an authoritative gateway
        // instruction. Otherwise the seller keeps the money and the buyer is told it is back.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VERIFIED);
        assertThat(simulated.isAuthoritative()).isFalse();
    }

    @Test
    void theServiceRefusesANonAuthoritativeGatewayThatWouldHappilyRefund() {
        // The guard in PaymentService and the guard in SimulatedPaymentGateway are independent
        // checks on the same invariant. Testing them together is what let an earlier revision
        // of refundIsRefusedWhileTheGatewayIsSimulated pass after the service-level check was
        // deleted: the gateway threw anyway, so the assertion was satisfied by the wrong
        // layer. This gateway answers isAuthoritative() false and *would* complete a refund, so
        // the only thing that can stop it is PaymentService's own check.
        stubSellerAccess();
        when(gatewayRegistry.forProvider("MPESA")).thenReturn(nonAuthoritativeButCompliantGateway);
        when(nonAuthoritativeButCompliantGateway.getName()).thenReturn("Test Provider");
        when(nonAuthoritativeButCompliantGateway.isAuthoritative()).thenReturn(false);

        assertThatThrownBy(() -> paymentService.refund(paymentId))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("cannot move money");

        // The gateway was never called, and the payment was never written to.
        verify(nonAuthoritativeButCompliantGateway, never()).refund(anyString());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VERIFIED);
    }

    @Test
    void aSimulatedGatewayRefusesToFabricateARefundOnItsOwn() {
        SimulatedPaymentGateway simulated = new SimulatedPaymentGateway();

        assertThatThrownBy(() -> simulated.refund("MPESA-reference-1"))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("no money was ever moved");
    }

    @Test
    void simulatedGatewayReportsPendingRatherThanSettledOnPay() {
        GatewayResponse response = new SimulatedPaymentGateway().pay(
                new PaymentIntent("external-1", "idem-key-1", new BigDecimal("100.00"), "KES"));

        assertThat(response.status()).isEqualTo(GatewayStatus.PENDING);
        assertThat(response.reference()).isNotBlank();
    }

    @Test
    void anAuthoritativeGatewayMayStillRefund() {
        stubSellerAccess();
        when(gatewayRegistry.forProvider("MPESA")).thenReturn(authoritativeGateway);
        when(authoritativeGateway.isAuthoritative()).thenReturn(true);
        when(authoritativeGateway.refund("MPESA-reference-1"))
                .thenReturn(new GatewayResponse("MPESA-reference-1", GatewayStatus.SUCCESS));

        paymentService.refund(paymentId);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(authoritativeGateway).refund("MPESA-reference-1");
    }

    // ---------------------------------------------------------------------------------
    // Settlement attribution and order-state guards on the create/verify paths.
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a manual settlement records who made it, so a paid order is adjudicable")
    void aManualSettlementIsAttributedToItsActor() {
        User buyer = buyer();
        User seller = seller();
        Order order = order(buyer, seller, OrderStatus.CONFIRMED);
        Payment pending = new Payment(
                order, PaymentStatus.PENDING, "MPESA", new BigDecimal("100.00"), "idem-attribute");
        pending.recordGatewayDetails("external-attr", "MPESA-ref-attr");
        ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());

        when(currentUser.required()).thenReturn(
                new AuthenticatedUser(sellerId, "seller@example.com", "hash", UserRole.FARMER));
        when(paymentRepository.findByIdempotencyKey("idem-attribute")).thenReturn(Optional.of(pending));

        paymentService.verify("idem-attribute");

        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.VERIFIED);
        assertThat(pending.getVerifiedByUserId())
                .as("the seller whose word settled the payment must be named on the row")
                .isEqualTo(sellerId);
        assertThat(pending.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("a cancelled order cannot be settled")
    void aCancelledOrderCannotBeSettled() {
        // Cancelling releases the reserved stock and terminates the order. Verifying a payment
        // against it afterwards marks a customer order paid for goods nobody will fulfil.
        User buyer = buyer();
        User seller = seller();
        Order order = order(buyer, seller, OrderStatus.CANCELLED);
        Payment pending = new Payment(
                order, PaymentStatus.PENDING, "MPESA", new BigDecimal("100.00"), "idem-cancelled");
        pending.recordGatewayDetails("external-c", "MPESA-ref-c");
        ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());

        when(currentUser.required()).thenReturn(
                new AuthenticatedUser(sellerId, "seller@example.com", "hash", UserRole.FARMER));
        when(paymentRepository.findByIdempotencyKey("idem-cancelled")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> paymentService.verify("idem-cancelled"))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("CANCELLED");

        assertThat(pending.getStatus())
                .as("a refused settlement must leave the payment exactly as it was")
                .isEqualTo(PaymentStatus.PENDING);
        verify(orderService, never()).markCustomerOrderPaidWhenComplete(any());
    }

    @Test
    @DisplayName("a second live payment for one order is refused")
    void aSecondLivePaymentForOneOrderIsRefused() {
        // The idempotency key stops a retried request duplicating a payment. It does nothing
        // about a second request that picks a different key, which the buyer controls.
        User buyer = buyer();
        User seller = seller();
        Order order = order(buyer, seller, OrderStatus.PLACED);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());

        when(currentUser.required()).thenReturn(
                new AuthenticatedUser(buyer.getId(), "buyer@example.com", "hash", UserRole.BUYER));
        when(orderRepository.findWithActorsById(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByIdempotencyKey("idem-second")).thenReturn(Optional.empty());
        when(paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.PENDING))
                .thenReturn(true);

        assertThatThrownBy(() -> paymentService.create(
                        new com.kilivana.payments.api.CreatePaymentRequest(
                                order.getId(), "MPESA", "idem-second")))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("already has a payment");

        verify(gatewayRegistry, never()).forProvider(anyString());
    }

    @ParameterizedTest(name = "an order in {0} cannot be paid")
    @EnumSource(value = OrderStatus.class, names = {"CANCELLED", "REJECTED", "COMPLETED"})
    @DisplayName("a terminal order cannot be paid at all")
    void aTerminalOrderCannotBePaid(OrderStatus terminalStatus) {
        User buyer = buyer();
        User seller = seller();
        Order order = order(buyer, seller, terminalStatus);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());

        when(currentUser.required()).thenReturn(
                new AuthenticatedUser(buyer.getId(), "buyer@example.com", "hash", UserRole.BUYER));
        when(orderRepository.findWithActorsById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> paymentService.create(
                        new com.kilivana.payments.api.CreatePaymentRequest(
                                order.getId(), "MPESA", "idem-terminal")))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining(terminalStatus.name());
    }

    private User buyer() {
        User buyer = new User("buyer@example.com", "hash", "Buyer", "+254700000008",
                UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(buyer, "id", UUID.randomUUID());
        return buyer;
    }

    private User seller() {
        User seller = new User("seller@example.com", "hash", "Seller", "+254700000009",
                UserRole.FARMER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(seller, "id", sellerId);
        return seller;
    }

    private Order order(User buyer, User seller, OrderStatus status) {
        return new Order(buyer, seller, new CustomerOrder(buyer, "KES", null, null),
                status, new BigDecimal("100.00"), "KES");
    }
}
