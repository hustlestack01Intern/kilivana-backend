package com.kilivana.paymentgateway;

import com.kilivana.common.exception.BusinessConflictException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PaymentGatewayRegistry {

    private final List<PaymentGateway> gateways;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways) {
        this.gateways = gateways;
    }

    public PaymentGateway forProvider(String provider) {
        return gateways.stream()
                .filter(gateway -> gateway.getName().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> new BusinessConflictException("Unsupported payment provider: " + provider));
    }
}