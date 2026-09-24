package com.kilivana.paymentgateway;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MpesaStubPaymentGateway implements PaymentGateway {

    @Override
    public String getName() {
        return "MPESA";
    }

    @Override
    public GatewayResponse pay(PaymentIntent intent) {
        return new GatewayResponse("MPESA-" + UUID.randomUUID(), GatewayStatus.SUCCESS);
    }

    @Override
    public GatewayResponse refund(String reference) {
        return new GatewayResponse(reference, GatewayStatus.SUCCESS);
    }
}