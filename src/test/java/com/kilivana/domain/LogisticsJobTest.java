package com.kilivana.domain;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.logistics.domain.LogisticsJob;
import com.kilivana.logistics.domain.LogisticsJobStatus;
import com.kilivana.orders.domain.CustomerOrder;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogisticsJobTest {

    private LogisticsJob job() {
        User buyer = new User("buyer@example.com", "hash", "Buyer", "+254700000002", UserRole.BUYER, UserStatus.ACTIVE);
        User seller = new User("seller@example.com", "hash", "Seller", "+254700000003", UserRole.FARMER, UserStatus.ACTIVE);
        Order order = new Order(buyer, seller, new CustomerOrder(buyer, "KES", null, null),
                OrderStatus.CONFIRMED, new BigDecimal("100.00"), "KES");
        return new LogisticsJob(order, "Handle with care", OffsetDateTime.now().plusDays(2),
                UUID.randomUUID(), UUID.randomUUID());
    }

    private User driver() {
        return new User("driver@example.com", "hash", "Driver", "+254700000004", UserRole.DRIVER, UserStatus.ACTIVE);
    }

    @Test
    void shouldStartAsPendingAcceptance() {
        assertThat(job().getStatus()).isEqualTo(LogisticsJobStatus.PENDING_ACCEPTANCE);
    }

    @Test
    void driverShouldAcceptAndTraverseDeliveryFlow() {
        LogisticsJob job = job();
        User driver = driver();

        job.updateStatus(LogisticsJobStatus.ACCEPTED, driver);
        job.updateStatus(LogisticsJobStatus.AT_PICKUP, driver);
        job.updateStatus(LogisticsJobStatus.PICKED_UP, driver);
        job.updateStatus(LogisticsJobStatus.IN_TRANSIT, driver);
        job.submitProofOfDelivery("pods/abc", "sig.png", "image/png", "Jane Doe");
        job.updateStatus(LogisticsJobStatus.DELIVERED, driver);

        assertThat(driver).isEqualTo(job.getDriver());
        assertThat(job.getStatus()).isEqualTo(LogisticsJobStatus.DELIVERED);
        assertThat(job.getDeliveredAt()).isNotNull();
        assertThat(job.getPodSubmittedAt()).isNotNull();
        assertThat(job.getDeliveredTo()).isEqualTo("Jane Doe");
    }

    @Test
    void onlyDriverCanAccept() {
        LogisticsJob job = job();
        User seller = job.getOrder().getSeller();

        assertThatThrownBy(() -> job.updateStatus(LogisticsJobStatus.ACCEPTED, seller))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("Only a driver");
    }

    @Test
    void cannotBeDeliveredWithoutProofOfDelivery() {
        LogisticsJob job = job();
        User driver = driver();
        job.updateStatus(LogisticsJobStatus.ACCEPTED, driver);
        job.updateStatus(LogisticsJobStatus.AT_PICKUP, driver);
        job.updateStatus(LogisticsJobStatus.PICKED_UP, driver);
        job.updateStatus(LogisticsJobStatus.IN_TRANSIT, driver);

        assertThatThrownBy(() -> job.updateStatus(LogisticsJobStatus.DELIVERED, driver))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("Proof of delivery");
    }

    @Test
    void shouldRejectSkippedTransitions() {
        LogisticsJob job = job();

        assertThatThrownBy(() -> job.updateStatus(LogisticsJobStatus.PICKED_UP, driver()))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("Invalid logistics job status transition");
    }

    @Test
    void shouldAllowFailureMidTransitWithoutDeliveredAt() {
        LogisticsJob job = job();
        User driver = driver();
        job.updateStatus(LogisticsJobStatus.ACCEPTED, driver);
        job.updateStatus(LogisticsJobStatus.AT_PICKUP, driver);
        job.updateStatus(LogisticsJobStatus.PICKED_UP, driver);
        job.updateStatus(LogisticsJobStatus.IN_TRANSIT, driver);

        job.updateStatus(LogisticsJobStatus.FAILED, driver);

        assertThat(job.getStatus()).isEqualTo(LogisticsJobStatus.FAILED);
        assertThat(job.getDeliveredAt()).isNull();
    }

    @Test
    void sellerCanCancelBeforeAcceptance() {
        LogisticsJob job = job();
        User seller = job.getOrder().getSeller();

        job.updateStatus(LogisticsJobStatus.CANCELLED, seller);

        assertThat(job.getStatus()).isEqualTo(LogisticsJobStatus.CANCELLED);
    }
}