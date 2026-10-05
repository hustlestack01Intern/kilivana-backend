package com.kilivana.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Attaches a Google identity to the account the caller is already authenticated as.
 *
 * <p>Both fields are required even when only one is wrong, because the request must spend
 * the same work either way: {@code googleIdToken} and {@code password} are checked in a fixed
 * order that does not depend on which is present, and neither branch answers differently from
 * the other.
 *
 * @param googleIdToken the Google credential to bind.
 * @param password the existing account's password. Proves the caller is authorised to attach
 *         an identity here. Bounded at 72 because bcrypt silently truncates beyond that, and
 *         accepting a longer one would let two different passwords authenticate the same
 *         account.
 */
public record AccountLinkRequest(
        @Schema(description = "The Google ID token to bind to the authenticated account.")
        @NotBlank @Size(max = 4096) String googleIdToken,
        @NotBlank @Size(max = 72) String password) {
}