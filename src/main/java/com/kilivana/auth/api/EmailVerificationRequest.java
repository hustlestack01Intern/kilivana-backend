package com.kilivana.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Exchanges mailbox proof for a proven onboarding ticket.
 *
 * <p>No email field, by design. Accepting a client-supplied address here would turn the
 * endpoint into a mail cannon aimed at arbitrary third parties, and accepting one alongside
 * the token would let a caller claim a mailbox they proved nothing about. The address is read
 * from the ticket, and the token must name the same one.
 *
 * <p>Both halves are required. The ticket proves which flow is being completed and which
 * Google identity it belongs to; the token proves the mailbox. Either alone would be a hole —
 * the ticket alone would bypass verification entirely, and the token alone would let anyone
 * who intercepted one message bind an identity.
 */
public record EmailVerificationRequest(
        @Schema(description = "The unproven onboarding ticket returned by the Google handshake.")
        @NotBlank @Size(max = 512) String onboardingTicket,

        @Schema(description = "The single-use token from the verification email.")
        @NotBlank @Size(max = 512) String token) {
}