package com.kilivana.domain;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.orders.domain.CustomerOrder;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.payments.domain.Payment;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private Payment payment(PaymentStatus status) {
        User buyer = new User("buyer@example.com", "hash", "Buyer", "+254700000006", UserRole.BUYER, UserStatus.ACTIVE);
        User seller = new User("seller@example.com", "hash", "Seller", "+254700000007", UserRole.FARMER, UserStatus.ACTIVE);
        Order order = new Order(buyer, seller, new CustomerOrder(buyer, "KES", null, null),
                OrderStatus.CONFIRMED, new BigDecimal("100.00"), "KES");
        return new Payment(order, status, "MPESA", new BigDecimal("100.00"), "idem-key-1");
    }

    @Test
    void markVerifiedShouldOnlyApplyToPending() {
        Payment payment = payment(PaymentStatus.PENDING);

        payment.markVerified();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VERIFIED);
    }

    @Test
    void markFailedShouldOnlyApplyToPending() {
        Payment payment = payment(PaymentStatus.PENDING);

        payment.markFailed();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void refundShouldOnlyApplyToVerified() {
        Payment payment = payment(PaymentStatus.VERIFIED);

        payment.refund();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void shouldRejectRefundingAnUnverifiedPayment() {
        Payment payment = payment(PaymentStatus.PENDING);

        assertThatThrownBy(payment::refund)
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("verified");
    }

    @Test
    void shouldRejectFailingAnAlreadyVerifiedPayment() {
        Payment payment = payment(PaymentStatus.VERIFIED);

        assertThatThrownBy(payment::markFailed)
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("pending");
    }

    @Test
    void shouldNotRefundTwice() {
        Payment payment = payment(PaymentStatus.VERIFIED);
        payment.refund();

        assertThatThrownBy(payment::refund)
                .isInstanceOf(BusinessConflictException.class);
    }
}