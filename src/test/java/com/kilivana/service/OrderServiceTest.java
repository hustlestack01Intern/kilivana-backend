package com.kilivana.service;

import com.kilivana.addresses.repository.AddressRepository;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.products.domain.Product;
import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.domain.SellerType;
import com.kilivana.products.repository.ProductRepository;
import com.kilivana.orders.api.CreateOrderRequest;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.orders.api.UpdateOrderStatusRequest;
import com.kilivana.orders.domain.CartItem;
import com.kilivana.orders.domain.CustomerOrder;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.domain.OrderItem;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.orders.repository.CartItemRepository;
import com.kilivana.orders.repository.CustomerOrderRepository;
import com.kilivana.orders.repository.OrderItemRepository;
import com.kilivana.orders.repository.OrderRepository;
import com.kilivana.orders.service.OrderService;
import com.kilivana.orders.service.DeliveryFeeService;
import com.kilivana.payments.domain.Payment;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.payments.repository.PaymentRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private CustomerOrderRepository customerOrderRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OrderService orderService;

    private final UUID buyerId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();
    private final UUID outsiderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID customerOrderId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private User buyer;
    private User seller;
    private Product product;
    private CustomerOrder customerOrder;
    private Order order;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository,
                orderItemRepository,
                customerOrderRepository,
                cartItemRepository,
                productRepository,
                userRepository,
                addressRepository,
                paymentRepository,
                currentUser,
                new DeliveryFeeService(BigDecimal.ONE, BigDecimal.ZERO),
                eventPublisher);

        buyer = user(buyerId, UserRole.BUYER);
        seller = user(sellerId, UserRole.FARMER);
        product = new Product(
                seller,
                SellerType.FARMER,
                Sector.FARM_PRODUCE,
                ProductStatus.ACTIVE,
                null,
                "Maize",
                "Dry maize",
                "kg",
                new BigDecimal("42.50"),
                new BigDecimal("100.00"),
                null,
                new BigDecimal("1.00"));
        ReflectionTestUtils.setField(product, "id", productId);

        customerOrder = new CustomerOrder(buyer, "KES", null, null);
        ReflectionTestUtils.setField(customerOrder, "id", customerOrderId);

        order = new Order(buyer, seller, customerOrder, OrderStatus.PLACED, new BigDecimal("1062.50"), "KES");
        ReflectionTestUtils.setField(order, "id", orderId);
    }

    private User user(UUID id, UserRole role) {
        User user = new User("user" + id + "@example.com", "hash", "User", "+254700000000", role, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private AuthenticatedUser auth(UUID id, UserRole role) {
        return new AuthenticatedUser(id, "user" + id + "@example.com", "hash", role);
    }

    private OrderItem item(Order order, BigDecimal quantity) {
        return new OrderItem(order, product, quantity, product.getUnitPrice(),
                product.getUnitPrice().multiply(quantity));
    }

    private CartItem cartItem(BigDecimal quantity) {
        return new CartItem(buyer, product, quantity);
    }

    private void stubOrderLookup(Order orderToReturn) {
        when(orderRepository.findWithActorsById(orderId)).thenReturn(Optional.of(orderToReturn));
    }

    private Order deliveredOrder() {
        Order delivered = new Order(buyer, seller, customerOrder,
                OrderStatus.DELIVERED, new BigDecimal("1062.50"), "KES");
        ReflectionTestUtils.setField(delivered, "id", orderId);
        return delivered;
    }

    private void stubReleaseFlow(List<OrderItem> items) {
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(items);
        when(productRepository.findWithOwnerByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(paymentRepository.findByOrderId(orderId)).thenReturn(List.of());
    }

    @Test
    void createShouldReserveQuantityAndPlaceOrder() {
        User supplier = user(supplierId, UserRole.SUPPLIER);
        when(currentUser.required()).thenReturn(auth(supplierId, UserRole.SUPPLIER));
        when(userRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
        when(productRepository.findWithOwnerByIdForUpdate(productId)).thenReturn(Optional.of(product));

        OrderResponse response = orderService.create(new CreateOrderRequest(productId, new BigDecimal("25.00"), "KES"));

        assertThat(product.getAvailableQuantity()).isEqualByComparingTo("75.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("1062.50");
        assertThat(response.currency()).isEqualTo("KES");
    }

    @Test
    void createShouldRejectOrderingOwnProduct() {
        User supplierOwner = user(supplierId, UserRole.SUPPLIER);
        Product ownProduct = new Product(
                supplierOwner,
                SellerType.SUPPLIER,
                Sector.AGRI_INPUTS,
                ProductStatus.ACTIVE,
                null,
                "DAP fertiliser",
                "50kg bag",
                "bag",
                new BigDecimal("30.00"),
                new BigDecimal("50.00"),
                null,
                new BigDecimal("1.00"));
        ReflectionTestUtils.setField(ownProduct, "id", productId);
        when(currentUser.required()).thenReturn(auth(supplierId, UserRole.SUPPLIER));
        when(userRepository.findById(supplierId)).thenReturn(Optional.of(supplierOwner));
        when(productRepository.findWithOwnerByIdForUpdate(productId)).thenReturn(Optional.of(ownProduct));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(productId, new BigDecimal("25.00"), "KES")))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("your own product");
    }

    @Test
    void createShouldRejectNonBuyerOrSupplierRole() {
        when(currentUser.required()).thenReturn(auth(outsiderId, UserRole.ADMIN));
        when(userRepository.findById(outsiderId)).thenReturn(Optional.of(user(outsiderId, UserRole.ADMIN)));
        when(productRepository.findWithOwnerByIdForUpdate(productId)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(productId, new BigDecimal("25.00"), "KES")))
                .isInstanceOf(UnauthorizedOperationException.class)
                .hasMessageContaining("buyers and suppliers");
    }

    @Test
    void onlySellerCanMoveStatus() {
        stubOrderLookup(order);
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(currentUser.required()).thenReturn(auth(outsiderId, UserRole.ADMIN));

        assertThatThrownBy(() -> orderService.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.CONFIRMED)))
                .isInstanceOf(UnauthorizedOperationException.class)
                .hasMessageContaining("seller");
    }

    @Test
    void sellerCanConfirmPlacedOrder() {
        stubOrderLookup(order);
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(currentUser.required()).thenReturn(auth(sellerId, UserRole.FARMER));

        OrderResponse response = orderService.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.CONFIRMED));

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void buyerCannotUseSellerStatuses() {
        stubOrderLookup(order);
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(currentUser.required()).thenReturn(auth(buyerId, UserRole.BUYER));

        assertThatThrownBy(() -> orderService.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.CONFIRMED)))
                .isInstanceOf(UnauthorizedOperationException.class);
    }

    @Test
    void buyerCancellingPlacedOrderReleasesReservedQuantity() {
        stubOrderLookup(order);
        when(currentUser.required()).thenReturn(auth(buyerId, UserRole.BUYER));
        stubReleaseFlow(List.of(item(order, new BigDecimal("25.00"))));

        OrderResponse response = orderService.cancel(orderId);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(product.getAvailableQuantity()).isEqualByComparingTo("125.00");
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void cancellingOrderAutoRefundsVerifiedPayments() {
        Payment verifiedPayment = new Payment(order, PaymentStatus.VERIFIED, "MPESA",
                new BigDecimal("1062.50"), "idem-key");
        stubOrderLookup(order);
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item(order, new BigDecimal("25.00"))));
        when(productRepository.findWithOwnerByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(paymentRepository.findByOrderId(orderId)).thenReturn(List.of(verifiedPayment));
        when(currentUser.required()).thenReturn(auth(buyerId, UserRole.BUYER));

        orderService.cancel(orderId);

        assertThat(verifiedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void rejectingPlacedOrderReleasesReservedQuantity() {
        stubOrderLookup(order);
        when(currentUser.required()).thenReturn(auth(sellerId, UserRole.FARMER));
        stubReleaseFlow(List.of(item(order, new BigDecimal("25.00"))));

        OrderResponse response = orderService.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.REJECTED));

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(product.getAvailableQuantity()).isEqualByComparingTo("125.00");
    }

    @Test
    void buyerCannotUseUpdateStatusForCompletion() {
        stubOrderLookup(deliveredOrder());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(currentUser.required()).thenReturn(auth(buyerId, UserRole.BUYER));

        assertThatThrownBy(() -> orderService.updateStatus(orderId, new UpdateOrderStatusRequest(OrderStatus.COMPLETED)))
                .isInstanceOf(UnauthorizedOperationException.class);
    }

    @Test
    void buyerCannotCancelADeliveredOrder() {
        stubOrderLookup(deliveredOrder());
        when(currentUser.required()).thenReturn(auth(buyerId, UserRole.BUYER));

        assertThatThrownBy(() -> orderService.cancel(orderId))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    @Test
    void buyerCanConfirmReceiptOfDeliveredOrder() {
        stubOrderLookup(deliveredOrder());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(currentUser.required()).thenReturn(auth(buyerId, UserRole.BUYER));

        OrderResponse response = orderService.confirmReceipt(orderId);

        assertThat(response.status()).isEqualTo(OrderStatus.COMPLETED);
    }
}