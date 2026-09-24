package com.kilivana.paymentgateway.service;

import com.kilivana.common.event.PaymentStatusChangedEvent;
import com.kilivana.orders.service.OrderService;
import com.kilivana.paymentgateway.api.PaymentWebhookRequest;
import com.kilivana.payments.domain.Payment;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.payments.repository.PaymentRepository;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final PaymentRepository paymentRepository;
    private final OrderService orderService;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentWebhookService(
            PaymentRepository paymentRepository,
            OrderService orderService,
            ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.orderService = orderService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void process(PaymentWebhookRequest request) {
        Optional<Payment> maybePayment = paymentRepository.findByExternalIdForUpdate(request.externalId());
        if (maybePayment.isEmpty()) {
            log.warn("Ignoring webhook for unknown externalId={} event={}", request.externalId(), request.eventType());
            return;
        }

        Payment payment = maybePayment.get();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Ignoring duplicate webhook externalId={} status={}", request.externalId(), payment.getStatus());
            return;
        }

        PaymentStatus previous = payment.getStatus();
        switch (request.eventType()) {
            case VERIFIED -> {
                payment.markVerified();
                eventPublisher.publishEvent(new PaymentStatusChangedEvent(
                        payment.getId(),
                        payment.getOrder().getId(),
                        previous,
                        payment.getStatus(),
                        OffsetDateTime.now()));
                orderService.markCustomerOrderPaidWhenComplete(payment.getOrder().getId());
            }
            case FAILED -> {
                payment.markFailed();
                eventPublisher.publishEvent(new PaymentStatusChangedEvent(
                        payment.getId(),
                        payment.getOrder().getId(),
                        previous,
                        payment.getStatus(),
                        OffsetDateTime.now()));
            }
        }
    }
}