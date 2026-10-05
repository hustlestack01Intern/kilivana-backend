package com.kilivana.auth.api;

import com.kilivana.users.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Completes registration for a Google identity whose mailbox has been proven, or asserted by
 * Google itself.
 *
 * <p>No email field, and no password. The address comes from the ticket and the account has
 * no password because it was never given one. The bounds on the role fields are copied
 * verbatim from {@link SignupRequest} rather than re-derived, so the two registration paths
 * cannot drift apart on what a supplier's business name may look like.
 *
 * <p>There is deliberately no adapter that turns this into a {@link SignupRequest}: doing so
 * would mean passing nulls through a record whose validation annotations demand both an email
 * and a password, and the rule-checking code would then hold a null-tolerant object it was
 * never designed for. {@code AuthService} exposes its role rules as plain arguments instead,
 * so both paths call literally the same method.
 */
public record GoogleOnboardingRequest(
        @NotBlank @Size(max = 512) String onboardingTicket,
        @Schema(allowableValues = {"BUYER", "FARMER", "SUPPLIER", "INSPECTOR"})
        @NotNull UserRole role,
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Size(max = 20) String phoneNumber,
        @Size(max = 150) String businessName,
        @Size(max = 150) String farmName,
        @Size(max = 200) String farmLocation,
        @Schema(description = "Required and nonblank for INSPECTOR only; trimmed before storage.")
        @Size(max = 100) String employeeCode) {
}