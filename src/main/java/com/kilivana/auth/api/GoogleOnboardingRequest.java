package com.kilivana.auth.api;

import com.kilivana.users.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

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
 * <p>{@code username} is collected, and required, exactly as {@link SignupRequest} collects it —
 * same bounds, same pattern, same canonicalising constructor, and the same
 * {@link com.kilivana.users.service.AccountIdentity#username} check on the way into the database.
 * It used to be absent and server-generated, on the reasoning that a name nobody chose is a name
 * nobody can act on: it is a handle the person did not pick, appears on their own account without
 * their agreement, and cannot be changed afterwards. That reasoning answered the wrong question.
 * The real requirement is that <em>taken</em> names not be enumerable, and generating satisfies it
 * at the cost of the feature; collecting satisfies it without that cost, provided the answer does
 * not distinguish taken from free.
 *
 * <p>The pattern here is a <em>shape</em> check and cannot leak availability — a name that fails
 * it is malformed regardless of whether it is taken — so it fails fast at the boundary like every
 * other annotated field here. Whether a well-formed name is available is a separate question
 * answered in {@code AuthService}, and that is the only layer allowed to answer it.
 *
 * <p>{@code region} is required for the same reason: it cannot be invented, and a server-generated
 * county would put a Kenyan supplier in the wrong one.
 */
public record GoogleOnboardingRequest(
        @NotBlank @Size(max = 512) String onboardingTicket,
        @Schema(description = "Globally unique; trimmed and lowercased using Locale.ROOT. 3-30 characters; first character is a lowercase ASCII letter, remaining characters are lowercase ASCII letters, digits or underscore. Not a login identifier.")
        @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "[a-z][a-z0-9_]{2,29}") String username,
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
    public GoogleOnboardingRequest {
        username = username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}