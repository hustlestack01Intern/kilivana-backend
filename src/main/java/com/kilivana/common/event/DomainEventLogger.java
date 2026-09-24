package com.kilivana.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DomainEventLogger {

    private static final Logger log = LoggerFactory.getLogger(DomainEventLogger.class);

    @EventListener
    public void on(OrderStatusChangedEvent event) {
        log.info("DomainEvent order[id={}] changed {} -> {}", event.orderId(), event.previousStatus(), event.newStatus());
    }

    @EventListener
    public void on(PaymentStatusChangedEvent event) {
        log.info("DomainEvent payment[id={}] changed {} -> {}", event.paymentId(), event.previousStatus(), event.newStatus());
    }

    @EventListener
    public void on(InspectionCompletedEvent event) {
        log.info("DomainEvent inspection[id={}] listing[id={}] passed={}", event.inspectionId(), event.listingId(), event.passed());
    }

    @EventListener
    public void on(ContactMessageReceivedEvent event) {
        log.info("DomainEvent contactMessage[id={}] from {} subject={}", event.messageId(), event.email(), event.subject());
    }
}