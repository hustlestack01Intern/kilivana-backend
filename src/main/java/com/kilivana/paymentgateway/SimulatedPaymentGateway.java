package com.kilivana.paymentgateway;

import com.kilivana.common.exception.BusinessConflictException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The only {@link PaymentGateway} this deployment ships. It moves no money.
 *
 * <p>It exists so the marketplace flow has something to create a payment intent against. It
 * deliberately reports {@link GatewayStatus#PENDING} rather than {@code SUCCESS}: a synchronous
 * {@code SUCCESS} from a stub that never contacted a bank is the exact shape of a money-forgery
 * primitive, and the previous settlement path that trusted it has been removed.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    @Override
    public String getName() {
        return "MPESA";
    }

    @Override
    public boolean isAuthoritative() {
        return false;
    }

    @Override
    public GatewayResponse pay(PaymentIntent intent) {
        return new GatewayResponse("MPESA-" + UUID.randomUUID(), GatewayStatus.PENDING);
    }

    /**
     * Refuses rather than faking a success. Returning {@code SUCCESS} here without contacting
     * M-PESA would write a REFUNDED status onto money the seller still holds, and — because
     * {@code cancel} also reached the refund path — would have let a buyer do it unilaterally.
     */
    @Override
    public GatewayResponse refund(String reference) {
        throw new BusinessConflictException(
                "Refunds are unavailable: the payment gateway is simulated and no money was ever moved");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announceThatNoMoneyMoves() {
        log.info("SIMULATED PAYMENT GATEWAY ACTIVE (provider '{}'): no funds are captured, settled or "
                + "refunded, and no payment reaches VERIFIED without a manual seller action. "
                + "Do not deploy this build where real money is expected.", getName());
    }
}
