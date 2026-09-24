package com.kilivana.domain;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.inspection.domain.Inspection;
import com.kilivana.inspection.domain.InspectionResult;
import com.kilivana.inspection.domain.InspectionStatus;
import com.kilivana.products.domain.Product;
import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.domain.SellerType;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionTest {

    private Inspection inspection() {
        User inspector = new User("inspector@example.com", "hash", "Inspector", "+254700000004", UserRole.INSPECTOR, UserStatus.ACTIVE);
        User owner = new User("farmer@example.com", "hash", "Farmer", "+254700000005", UserRole.FARMER, UserStatus.ACTIVE);
        Product product = new Product(owner, SellerType.FARMER, Sector.FARM_PRODUCE, ProductStatus.ACTIVE,
                null, "Maize", "Dry maize", "kg", new BigDecimal("42.50"), new BigDecimal("100.00"),
                null, new BigDecimal("1.00"));
        return new Inspection(inspector, product, null, OffsetDateTime.now().plusDays(1));
    }

    @Test
    void shouldStartAsScheduled() {
        assertThat(inspection().getStatus()).isEqualTo(InspectionStatus.SCHEDULED);
    }

    @Test
    void concludeWithApprovalShouldRecordOutcomeAndDate() {
        Inspection inspection = inspection();

        inspection.markInProgress();
        inspection.conclude(InspectionResult.APPROVED, 4, "Clean, dry, well packed", OffsetDateTime.now().plusMonths(3));

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.PASSED);
        assertThat(inspection.isPassed()).isTrue();
        assertThat(inspection.getResult()).isEqualTo(InspectionResult.APPROVED);
        assertThat(inspection.getRating()).isEqualTo(4);
        assertThat(inspection.getPerformedAt()).isNotNull();
        assertThat(inspection.getNextInspectionDate()).isNotNull();
    }

    @Test
    void concludeWithRejectionShouldMarkAsFailed() {
        Inspection inspection = inspection();

        inspection.markInProgress();
        inspection.conclude(InspectionResult.REJECTED, 2, "Mold found in sample", null);

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.FAILED);
        assertThat(inspection.isPassed()).isFalse();
        assertThat(inspection.getPerformedAt()).isNotNull();
    }

    @Test
    void shouldRejectConclusionBeforeStart() {
        Inspection inspection = inspection();

        assertThatThrownBy(() -> inspection.conclude(InspectionResult.APPROVED, 4, "ok", null))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("Only in-progress inspections");
    }

    @Test
    void shouldRejectStartTwice() {
        Inspection inspection = inspection();
        inspection.markInProgress();

        assertThatThrownBy(inspection::markInProgress)
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void shouldRejectConclusionWithoutResult() {
        Inspection inspection = inspection();
        inspection.markInProgress();

        assertThatThrownBy(() -> inspection.conclude(null, 4, "ok", null))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("result is required");
    }

    @Test
    void shouldRejectChangesRequiredWithoutNextDate() {
        Inspection inspection = inspection();
        inspection.markInProgress();

        assertThatThrownBy(() -> inspection.conclude(InspectionResult.CHANGES_REQUIRED, 3, "minor", null))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("next inspection date");
    }

    @Test
    void shouldRejectRatingOutsideOneToFive() {
        Inspection inspection = inspection();
        inspection.markInProgress();

        assertThatThrownBy(() -> inspection.conclude(InspectionResult.APPROVED, 0, "ok", null))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("between 1 and 5");

        assertThatThrownBy(() -> inspection.conclude(InspectionResult.REJECTED, 6, "ok", null))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void shouldRejectConclusionFromConcludedState() {
        Inspection inspection = inspection();
        inspection.markInProgress();
        inspection.conclude(InspectionResult.APPROVED, 4, "ok", null);

        assertThatThrownBy(() -> inspection.conclude(InspectionResult.REJECTED, 1, "later", null))
                .isInstanceOf(BusinessConflictException.class);
    }
}