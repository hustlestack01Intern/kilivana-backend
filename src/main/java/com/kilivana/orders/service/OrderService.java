package com.kilivana.orders.service;

import com.kilivana.addresses.domain.Address;
import com.kilivana.addresses.repository.AddressRepository;
import com.kilivana.common.event.OrderStatusChangedEvent;
import com.kilivana.common.event.PaymentStatusChangedEvent;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.orders.api.AddToCartRequest;
import com.kilivana.orders.api.CartItemResponse;
import com.kilivana.orders.api.CartResponse;
import com.kilivana.orders.api.CheckoutRequest;
import com.kilivana.orders.api.CheckoutValidationRequest;
import com.kilivana.orders.api.CheckoutValidationResponse;
import com.kilivana.orders.api.CreateOrderRequest;
import com.kilivana.orders.api.CustomerOrderResponse;
import com.kilivana.orders.api.OrderItemResponse;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.orders.api.UpdateCartItemRequest;
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
import com.kilivana.payments.domain.Payment;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.payments.repository.PaymentRepository;
import com.kilivana.products.domain.Product;
import com.kilivana.products.repository.ProductRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private static final Set<OrderStatus> SELLER_STATUSES = Set.of(
            OrderStatus.CONFIRMED,
            OrderStatus.READY_FOR_PICKUP,
            OrderStatus.IN_PROGRESS,
            OrderStatus.DELIVERED,
            OrderStatus.REJECTED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final PaymentRepository paymentRepository;
    private final CurrentUser currentUser;
    private final DeliveryFeeService deliveryFeeService;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            CustomerOrderRepository customerOrderRepository,
            CartItemRepository cartItemRepository,
            ProductRepository productRepository,
            UserRepository userRepository,
            AddressRepository addressRepository,
            PaymentRepository paymentRepository,
            CurrentUser currentUser,
            DeliveryFeeService deliveryFeeService,
            ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.customerOrderRepository = customerOrderRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.paymentRepository = paymentRepository;
        this.currentUser = currentUser;
        this.deliveryFeeService = deliveryFeeService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CartItemResponse addToCart(AddToCartRequest request) {
        AuthenticatedUser actor = currentUser.required();
        User buyer = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Product product = productRepository.findWithOwnerByIdForUpdate(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        requireActiveProduct(product);
        validateOrderingRole(actor, product);

        CartItem item = cartItemRepository.findByBuyerIdAndProductId(actor.getId(), product.getId())
                .orElseGet(() -> new CartItem(buyer, product, BigDecimal.ZERO));
        item.addQuantity(request.quantity());
        cartItemRepository.save(item);
        return toCartItemResponse(item);
    }

    public CartResponse myCart() {
        AuthenticatedUser actor = currentUser.required();
        List<CartItem> items = cartItemRepository.findWithProductByBuyerId(actor.getId());
        return new CartResponse(
                items.stream().map(CartItem::getQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                "USD",
                items.stream().map(this::toCartItemResponse).toList());
    }

    @Transactional
    public CartItemResponse updateCartItem(UUID cartItemId, UpdateCartItemRequest request) {
        AuthenticatedUser actor = currentUser.required();
        CartItem item = requireCartItem(cartItemId, actor);
        item.updateQuantity(request.quantity());
        return toCartItemResponse(item);
    }

    @Transactional
    public void removeCartItem(UUID cartItemId) {
        AuthenticatedUser actor = currentUser.required();
        CartItem item = requireCartItem(cartItemId, actor);
        cartItemRepository.delete(item);
    }

    @Transactional
    public CustomerOrderResponse checkout(CheckoutRequest request) {
        AuthenticatedUser actor = currentUser.required();
        User buyer = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Address shipping = requireOwnedAddress(request.shippingAddressId(), actor);
        Address pickup = requireOwnedAddress(request.pickupAddressId(), actor);

        List<CartItem> items = cartItemRepository.findWithProductByBuyerId(actor.getId());
        if (items.isEmpty()) {
            throw new BusinessConflictException("Your cart is empty");
        }
        String currency = request.currency().trim().toUpperCase();

        CustomerOrder customerOrder = new CustomerOrder(
                buyer, currency, shipping.getId(), pickup.getId());
        customerOrderRepository.save(customerOrder);

        Map<UUID, List<CartItem>> bySeller = new LinkedHashMap<>();
        for (CartItem item : items) {
            Product product = item.getProduct();
            requireActiveProduct(product);
            validateOrderingRole(actor, product);
            productRepository.findWithOwnerByIdForUpdate(product.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"))
                    .reserveQuantity(item.getQuantity());
            bySeller.computeIfAbsent(product.getOwner().getId(), k -> new ArrayList<>()).add(item);
        }

        BigDecimal deliveryFee = deliveryFeeService.compute(pickup, shipping);

        List<OrderResponse> subOrders = new ArrayList<>();
        for (Map.Entry<UUID, List<CartItem>> entry : bySeller.entrySet()) {
            User seller = userRepository.findById(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Seller not found"));
            BigDecimal sellerSubTotal = entry.getValue().stream()
                    .map(i -> i.getProduct().getUnitPrice().multiply(i.getQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Order order = new Order(buyer, seller, customerOrder, OrderStatus.PLACED,
                    sellerSubTotal, deliveryFee, currency);
            orderRepository.save(order);

            List<OrderItem> orderItems = new ArrayList<>();
            for (CartItem cartItem : entry.getValue()) {
                Product product = cartItem.getProduct();
                BigDecimal lineTotal = product.getUnitPrice().multiply(cartItem.getQuantity());
                OrderItem orderItem = new OrderItem(order, product, cartItem.getQuantity(),
                        product.getUnitPrice(), lineTotal);
                orderItemRepository.save(orderItem);
                orderItems.add(orderItem);
            }
            subOrders.add(toResponse(order, orderItems));
        }

        cartItemRepository.deleteAll(items);
        return toCustomerOrderResponse(customerOrder, subOrders);
    }

    public CheckoutValidationResponse validate(CheckoutValidationRequest request) {
        AuthenticatedUser actor = currentUser.required();
        Address shipping = requireOwnedAddress(request.shippingAddressId(), actor);
        Address pickup = requireOwnedAddress(request.pickupAddressId(), actor);

        List<CartItem> items = cartItemRepository.findWithProductByBuyerId(actor.getId());
        if (items.isEmpty()) {
            throw new BusinessConflictException("Your cart is empty");
        }
        for (CartItem item : items) {
            requireActiveProduct(item.getProduct());
            validateOrderingRole(actor, item.getProduct());
        }
        String currency = request.currency().trim().toUpperCase();

        Map<UUID, List<CartItem>> bySeller = new LinkedHashMap<>();
        for (CartItem item : items) {
            bySeller.computeIfAbsent(item.getProduct().getOwner().getId(), k -> new ArrayList<>()).add(item);
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        List<CheckoutValidationResponse.SellerTotals> sellers = new ArrayList<>();
        for (Map.Entry<UUID, List<CartItem>> entry : bySeller.entrySet()) {
            BigDecimal sellerSubtotal = entry.getValue().stream()
                    .map(i -> i.getProduct().getUnitPrice().multiply(i.getQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            subtotal = subtotal.add(sellerSubtotal);
            String sellerName = entry.getValue().get(0).getProduct().getOwner().getFullName();
            sellers.add(new CheckoutValidationResponse.SellerTotals(
                    entry.getKey(),
                    sellerName,
                    sellerSubtotal,
                    deliveryFeeService.compute(pickup, shipping),
                    sellerSubtotal.add(deliveryFeeService.compute(pickup, shipping))));
        }

        return new CheckoutValidationResponse(
                actor.getId(),
                bySeller.size(),
                items.size(),
                subtotal,
                deliveryFeeService.compute(pickup, shipping).multiply(BigDecimal.valueOf(bySeller.size())),
                subtotal.add(deliveryFeeService.compute(pickup, shipping).multiply(BigDecimal.valueOf(bySeller.size()))),
                currency,
                sellers);
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        AuthenticatedUser actor = currentUser.required();
        User buyer = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String currency = request.currency().trim().toUpperCase();
        CustomerOrder customerOrder = new CustomerOrder(buyer, currency, null, null);
        customerOrderRepository.save(customerOrder);

        Product product = productRepository.findWithOwnerByIdForUpdate(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        requireActiveProduct(product);
        validateOrderingRole(actor, product);
        product.reserveQuantity(request.quantity());

        User seller = product.getOwner();
        BigDecimal subtotal = product.getUnitPrice().multiply(request.quantity());
        Order order = new Order(buyer, seller, customerOrder, OrderStatus.PLACED,
                subtotal, BigDecimal.ZERO, currency);
        orderRepository.save(order);

        OrderItem item = new OrderItem(order, product, request.quantity(), product.getUnitPrice(), subtotal);
        orderItemRepository.save(item);
        return toResponse(order, List.of(item));
    }

    public List<OrderResponse> myOrders() {
        AuthenticatedUser actor = currentUser.required();
        return orderRepository.findByBuyerIdOrSellerId(actor.getId(), actor.getId())
                .stream()
                .map(order -> toResponse(order, orderItemRepository.findByOrderId(order.getId())))
                .toList();
    }

    public OrderResponse getById(UUID orderId) {
        AuthenticatedUser actor = currentUser.required();
        Order order = orderRepository.findWithActorsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getBuyer().getId().equals(actor.getId()) && !order.getSeller().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only view your own orders");
        }
        return toResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    @Transactional
    public OrderResponse updateStatus(UUID orderId, UpdateOrderStatusRequest request) {
        AuthenticatedUser actor = currentUser.required();
        Order order = orderRepository.findWithActorsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);

        OrderStatus nextStatus = request.status();
        enforceSellerRules(order, actor, nextStatus);
        if (shouldReleaseReservedQuantity(order.getStatus(), nextStatus)) {
            releaseItems(items);
            refundVerifiedPayments(order);
        }
        OrderStatus previousStatus = order.getStatus();
        order.transitionTo(nextStatus);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                order.getId(),
                previousStatus,
                order.getStatus(),
                OffsetDateTime.now()));
        return toResponse(order, items);
    }

    @Transactional
    public OrderResponse confirmReceipt(UUID orderId) {
        AuthenticatedUser actor = currentUser.required();
        Order order = orderRepository.findWithActorsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getBuyer().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Only the buyer can confirm receipt");
        }
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessConflictException("Only delivered orders can be confirmed as received");
        }
        order.transitionTo(OrderStatus.COMPLETED);
        return toResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    @Transactional
    public OrderResponse cancel(UUID orderId) {
        AuthenticatedUser actor = currentUser.required();
        Order order = orderRepository.findWithActorsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getBuyer().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Only the buyer can cancel an order");
        }
        if (!isCancellableByBuyer(order.getStatus())) {
            throw new BusinessConflictException("Order cannot be cancelled in its current state");
        }
        if (order.getCustomerOrder().isCancellable() && order.getCustomerOrder().getStatus()
                == com.kilivana.orders.domain.CustomerOrderStatus.NEW) {
            order.getCustomerOrder().cancel();
        }
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        releaseItems(items);
        refundVerifiedPayments(order);
        OrderStatus previousStatus = order.getStatus();
        order.transitionTo(OrderStatus.CANCELLED);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                order.getId(),
                previousStatus,
                order.getStatus(),
                OffsetDateTime.now()));
        return toResponse(order, items);
    }

    private void releaseItems(List<OrderItem> items) {
        items.forEach(item -> productRepository.findWithOwnerByIdForUpdate(item.getProduct().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"))
                .releaseQuantity(item.getQuantity()));
    }

    private void refundVerifiedPayments(Order order) {
        paymentRepository.findByOrderId(order.getId()).stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.VERIFIED)
                .forEach(payment -> {
                    payment.refund();
                    eventPublisher.publishEvent(new PaymentStatusChangedEvent(
                            payment.getId(),
                            order.getId(),
                            PaymentStatus.VERIFIED,
                            PaymentStatus.REFUNDED,
                            OffsetDateTime.now()));
                });
    }

    @Transactional
    public void markCustomerOrderPaidWhenComplete(UUID orderId) {
        Order order = orderRepository.findWithActorsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        com.kilivana.orders.domain.CustomerOrder customerOrder = order.getCustomerOrder();
        if (customerOrder.getStatus() != com.kilivana.orders.domain.CustomerOrderStatus.NEW) {
            return;
        }
        boolean allCovered = orderRepository.findByCustomerOrderId(customerOrder.getId()).stream()
                .allMatch(this::isFullyPaid);
        if (allCovered) {
            customerOrder.markPaid();
        }
    }

    private boolean isFullyPaid(Order order) {
        BigDecimal verified = paymentRepository.findByOrderId(order.getId()).stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.VERIFIED)
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return verified.compareTo(order.getTotalAmount()) >= 0;
    }

    private void validateOrderingRole(AuthenticatedUser actor, Product product) {
        if (actor.getRole() != UserRole.BUYER && actor.getRole() != UserRole.SUPPLIER) {
            throw new UnauthorizedOperationException("Only buyers and suppliers can place orders");
        }
        if (product.getOwner().getId().equals(actor.getId())) {
            throw new BusinessConflictException("You cannot order your own product");
        }
    }

    private void requireActiveProduct(Product product) {
        if (product.getStatus() != com.kilivana.products.domain.ProductStatus.ACTIVE) {
            throw new BusinessConflictException("Product is not available for purchase");
        }
    }

    private void enforceSellerRules(Order order, AuthenticatedUser actor, OrderStatus nextStatus) {
        if (!order.getSeller().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Only the seller can move the order to that status");
        }
        if (!SELLER_STATUSES.contains(nextStatus)) {
            throw new UnauthorizedOperationException("Seller cannot move the order to that status");
        }
        if (!isValidTransition(order.getStatus(), nextStatus)) {
            throw new BusinessConflictException("Invalid order status transition");
        }
    }

    private boolean isValidTransition(OrderStatus current, OrderStatus next) {
        return switch (current) {
            case PLACED -> next == OrderStatus.CONFIRMED || next == OrderStatus.REJECTED;
            case CONFIRMED -> next == OrderStatus.IN_PROGRESS || next == OrderStatus.READY_FOR_PICKUP;
            case READY_FOR_PICKUP -> next == OrderStatus.IN_PROGRESS;
            case IN_PROGRESS -> next == OrderStatus.DELIVERED;
            case DELIVERED, COMPLETED, CANCELLED, REJECTED -> false;
        };
    }

    private boolean shouldReleaseReservedQuantity(OrderStatus current, OrderStatus next) {
        return next == OrderStatus.REJECTED && current != OrderStatus.REJECTED;
    }

    private boolean isCancellableByBuyer(OrderStatus status) {
        return status == OrderStatus.PLACED
                || status == OrderStatus.CONFIRMED
                || status == OrderStatus.READY_FOR_PICKUP
                || status == OrderStatus.IN_PROGRESS;
    }

    private CartItem requireCartItem(UUID cartItemId, AuthenticatedUser actor) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
        if (!item.getBuyer().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only manage your own cart items");
        }
        return item;
    }

    private Address requireOwnedAddress(UUID addressId, AuthenticatedUser actor) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));
        if (!address.getUser().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only use your own addresses");
        }
        return address;
    }

    private CartItemResponse toCartItemResponse(CartItem item) {
        Product product = item.getProduct();
        User seller = product.getOwner();
        return new CartItemResponse(
                item.getId(),
                product.getId(),
                product.getTitle(),
                seller.getId(),
                seller.getFullName(),
                product.getUnitPrice(),
                item.getQuantity(),
                product.getUnitPrice().multiply(item.getQuantity()),
                item.getCreatedAt());
    }

    private CustomerOrderResponse toCustomerOrderResponse(CustomerOrder customerOrder, List<OrderResponse> subOrders) {
        BigDecimal total = subOrders.stream()
                .map(OrderResponse::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CustomerOrderResponse(
                customerOrder.getId(),
                customerOrder.getBuyer().getId(),
                customerOrder.getStatus(),
                total,
                customerOrder.getCurrency(),
                customerOrder.getShippingAddressId(),
                customerOrder.getPickupAddressId(),
                customerOrder.getCreatedAt(),
                subOrders);
    }

    private OrderResponse toResponse(Order order, List<OrderItem> items) {
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
                items.stream()
                        .map(item -> new OrderItemResponse(
                                item.getProduct().getId(),
                                item.getProduct().getTitle(),
                                item.getQuantity(),
                                item.getUnitPrice(),
                                item.getLineTotal()))
                        .toList());
    }
}