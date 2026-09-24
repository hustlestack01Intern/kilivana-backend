package com.kilivana.paymentgateway;

public interface PaymentGateway {

    String getName();

    GatewayResponse pay(PaymentIntent intent);

    GatewayResponse refund(String reference);
}