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
 *
 * <p>{@code username} is absent, and that is the point. It is globally unique, so collecting it
 * would turn onboarding into a guessing game against a namespace the caller cannot enumerate —
 * a 409 here would tell an attacker that a name is taken, and a name is not something a person
 * arrives knowing they need. The server generates one instead, so the client never learns whether
 * it collided. {@code region} is the opposite case and is required: it cannot be invented.
 */
public record GoogleOnboardingRequest(
        @NotBlank @Size(max = 512) String onboardingTicket,
        @Schema(allowableValues = {"BUYER", "FARMER", "SUPPLIER", "INSPECTOR"})
        @NotNull UserRole role,
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Size(max = 20) String phoneNumber,
        @Schema(description = "Required for every public role; trimmed, nonblank region of at most 200 characters. Stored on the account, and for a FARMER it is also the profile's farm location.")
        @NotBlank @Size(max = 200) String region,
        @Size(max = 150) String businessName,
        @Size(max = 150) String farmName,
        @Size(max = 200) String farmLocation,
        @Schema(description = "Required and nonblank for INSPECTOR only; trimmed before storage.")
        @Size(max = 100) String employeeCode) {
}