package com.kilivana.paymentgateway;

public interface PaymentGateway {

    String getName();

    /**
     * Whether this adapter talks to a real provider and can therefore be trusted to move
     * money. Callers must not infer settlement from a non-authoritative adapter.
     */
    default boolean isAuthoritative() {
        return true;
    }

    GatewayResponse pay(PaymentIntent intent);

    GatewayResponse refund(String reference);
}
