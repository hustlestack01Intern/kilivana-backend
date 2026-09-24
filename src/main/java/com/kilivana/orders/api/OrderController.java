package com.kilivana.orders.api;

import com.kilivana.orders.api.CheckoutValidationRequest;
import com.kilivana.orders.api.CheckoutValidationResponse;
import com.kilivana.orders.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/cart")
    public ResponseEntity<CartItemResponse> addToCart(@Valid @RequestBody AddToCartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.addToCart(request));
    }

    @GetMapping("/cart/items")
    public ResponseEntity<CartResponse> myCart() {
        return ResponseEntity.ok(orderService.myCart());
    }

    @PatchMapping("/cart/items/{cartItemId}")
    public ResponseEntity<CartItemResponse> updateCartItem(
            @PathVariable UUID cartItemId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return ResponseEntity.ok(orderService.updateCartItem(cartItemId, request));
    }

    @DeleteMapping("/cart/items/{cartItemId}")
    public ResponseEntity<Void> removeCartItem(@PathVariable UUID cartItemId) {
        orderService.removeCartItem(cartItemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/cart/checkout")
    public ResponseEntity<CustomerOrderResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.checkout(request));
    }

    @PostMapping("/checkout/validate")
    public ResponseEntity<CheckoutValidationResponse> validateCheckout(
            @Valid @RequestBody CheckoutValidationRequest request) {
        return ResponseEntity.ok(orderService.validate(request));
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request));
    }

    @GetMapping("/orders/mine")
    public ResponseEntity<List<OrderResponse>> myOrders() {
        return ResponseEntity.ok(orderService.myOrders());
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderResponse> getById(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getById(orderId));
    }

    @PatchMapping("/orders/{orderId}/status")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable UUID orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        return ResponseEntity.ok(orderService.updateStatus(orderId, request));
    }

    @PostMapping("/orders/{orderId}/confirm-receipt")
    public ResponseEntity<OrderResponse> confirmReceipt(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.confirmReceipt(orderId));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.cancel(orderId));
    }
}