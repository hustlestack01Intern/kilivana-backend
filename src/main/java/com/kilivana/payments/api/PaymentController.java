package com.kilivana.payments.api;

import com.kilivana.payments.service.PaymentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.create(request));
    }

    @PostMapping("/verify")
    public ResponseEntity<PaymentResponse> verify(@Valid @RequestBody VerifyPaymentRequest request) {
        return ResponseEntity.ok(paymentService.verify(request.idempotencyKey()));
    }

    @GetMapping("/inbound")
    public ResponseEntity<List<PaymentResponse>> inboundPayments() {
        return ResponseEntity.ok(paymentService.myInboundPayments());
    }

    @GetMapping("/outbound")
    public ResponseEntity<List<PaymentResponse>> outboundPayments() {
        return ResponseEntity.ok(paymentService.myOutboundPayments());
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getById(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.getById(paymentId));
    }

    @PostMapping("/{paymentId}/fail")
    public ResponseEntity<PaymentResponse> fail(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.markFailed(paymentId));
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<PaymentResponse> refund(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.refund(paymentId));
    }
}
