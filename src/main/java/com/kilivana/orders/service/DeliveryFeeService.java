package com.kilivana.orders.service;

import com.kilivana.addresses.domain.Address;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Calculates the delivery fee for a seller sub-order between a pickup and a
 * delivery address. A flat base fee applies to every sub-order; when both
 * addresses carry coordinates the fee is distance-aware (base + rate per km).
 */
@Service
public class DeliveryFeeService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final BigDecimal flatFee;
    private final BigDecimal perKmRate;

    public DeliveryFeeService(
            @Value("${kilivana.commerce.delivery-fee.flat:5.00}") BigDecimal flatFee,
            @Value("${kilivana.commerce.delivery-fee.per-km:0.00}") BigDecimal perKmRate) {
        this.flatFee = flatFee;
        this.perKmRate = perKmRate;
    }

    public BigDecimal compute(Address pickup, Address delivery) {
        double km = distanceKm(pickup, delivery);
        if (km <= 0) {
            return flatFee;
        }
        return flatFee.add(perKmRate.multiply(BigDecimal.valueOf(km)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal flatFee() {
        return flatFee;
    }

    private double distanceKm(Address from, Address to) {
        if (from.getLatitude() == null || from.getLongitude() == null
                || to.getLatitude() == null || to.getLongitude() == null) {
            return 0;
        }
        double lat1 = Math.toRadians(from.getLatitude());
        double lat2 = Math.toRadians(to.getLatitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.getLongitude() - from.getLongitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}