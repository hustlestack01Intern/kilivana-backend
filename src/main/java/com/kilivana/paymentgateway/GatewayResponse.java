package com.kilivana.paymentgateway;

public record GatewayResponse(String reference, GatewayStatus status) {
}