package com.kilivana.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * The ticket that carries a proven mailbox into onboarding.
 *
 * <p>Returned in place of the {@code EMAIL_VERIFICATION_REQUIRED} status: at this point the
 * address is proven, so the client has something to act on rather than being told to check
 * its inbox again.
 *
 * @param onboardingTicket bound to exactly one address and redeemable once. Not an account
 *         credential: it carries no role, no user id and no authority, and completing
 *         onboarding still runs every role rule the ordinary signup path runs.
 * @param expiresAt when the ticket stops being redeemable.
 */
public record OnboardingTicketResponse(
        @Schema(description = "Single use, bound to the verified address.")
        String onboardingTicket,
        OffsetDateTime expiresAt) {
}