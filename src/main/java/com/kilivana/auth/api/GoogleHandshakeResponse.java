package com.kilivana.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * The outcome of a Google handshake.
 *
 * <p>Absent fields are omitted from the response rather than sent as nulls
 * ({@code jackson.default-property-inclusion: non_null}), so a client can branch on which
 * fields arrived instead of parsing a fixed shape with holes in it.
 *
 * @param status what happened. The only field that is ever populated, and the only one a
 *         client should branch on.
 * @param auth present only when {@code status} is {@code AUTHENTICATED}.
 * @param onboardingTicket present whenever the caller must complete onboarding. Under
 *         {@code EMAIL_VERIFICATION_REQUIRED} it is an <em>unproven</em> ticket: it carries the
 *         identity and the address so a verification email can be tied to this flow, and it
 *         cannot create an account, because {@code onboard} refuses any ticket whose mailbox is
 *         unproven. Only {@link #onboardingRequired} hands back one that can.
 * @param suggestedFullName Google's {@code name} claim, offered so the client can prefill. A
 *         suggestion only; the value the account stores is the one the caller submits and it is
 *         server-validated like any other.
 * @param expiresAt when {@code onboardingTicket} stops being redeemable. Absent otherwise.
 */
public record GoogleHandshakeResponse(
        @Schema(allowableValues = {
                "AUTHENTICATED",
                "ONBOARDING_REQUIRED",
                "EMAIL_VERIFICATION_REQUIRED",
                "ACCOUNT_LINK_REQUIRED"})
        String status,
        @Schema(description = "Present only when status is AUTHENTICATED.")
        AuthResponse auth,
        @Schema(description = "Present only when status is ONBOARDING_REQUIRED. Single use.")
        String onboardingTicket,
        @Schema(description = "Google's name claim, offered as a prefill suggestion.")
        String suggestedFullName,
        @Schema(description = "Expiry of onboardingTicket. Present only alongside it.")
        OffsetDateTime expiresAt,
        @Schema(description = "Present only when delivery is simulated, which is refused on the prod "
                + "profile. The verification token, handed back because no mail was actually sent.")
        String verificationToken) {

    public static final String AUTHENTICATED = "AUTHENTICATED";
    public static final String ONBOARDING_REQUIRED = "ONBOARDING_REQUIRED";
    public static final String EMAIL_VERIFICATION_REQUIRED = "EMAIL_VERIFICATION_REQUIRED";
    public static final String ACCOUNT_LINK_REQUIRED = "ACCOUNT_LINK_REQUIRED";

    public static GoogleHandshakeResponse authenticated(AuthResponse auth) {
        return new GoogleHandshakeResponse(AUTHENTICATED, auth, null, null, null, null);
    }

    public static GoogleHandshakeResponse onboardingRequired(
            String onboardingTicket,
            String suggestedFullName,
            OffsetDateTime expiresAt) {
        return new GoogleHandshakeResponse(
                ONBOARDING_REQUIRED, null, onboardingTicket, suggestedFullName, expiresAt, null);
    }

    /**
     * Carries the unproven ticket the caller needs in order to submit the verification token,
     * and deliberately no name suggestion: a caller who has not proven the mailbox gets nothing
     * that could be used to finish registration without it.
     *
     * @param verificationToken the token itself, present only under simulated delivery.
     */
    public static GoogleHandshakeResponse emailVerificationRequired(
            String unprovenTicket,
            OffsetDateTime expiresAt,
            String verificationToken) {
        return new GoogleHandshakeResponse(
                EMAIL_VERIFICATION_REQUIRED, null, unprovenTicket, null, expiresAt, verificationToken);
    }

    public static GoogleHandshakeResponse accountLinkRequired() {
        return new GoogleHandshakeResponse(ACCOUNT_LINK_REQUIRED, null, null, null, null, null);
    }
}