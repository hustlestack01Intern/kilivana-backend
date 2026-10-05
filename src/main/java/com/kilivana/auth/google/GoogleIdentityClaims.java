package com.kilivana.auth.google;

import java.util.Locale;

/**
 * What Google's ID token asserted, and nothing more.
 *
 * <p>{@code subject} is the only field that identifies anybody. The email is what Google
 * happened to say at the moment; it is evidence for {@link #authoritative()} and nothing is
 * resolved by it.
 */
public record GoogleIdentityClaims(
        String subject,
        String email,
        boolean emailVerified,
        String hostedDomain,
        String name) {

    /**
     * Google's own rule for when an {@code email} claim may be trusted for its own sake:
     * a Gmail address always, anything else only when Google also says the address is
     * verified <em>and</em> the account sits on a hosted (Workspace) domain.
     *
     * <p>Absent {@code email_verified} is false, never "assume true". A token that omits the
     * claim and a token that sets it false are indistinguishable to this code, and both mean
     * the address has not been proven.
     */
    public boolean authoritative() {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("@gmail.com")) {
            return true;
        }
        return emailVerified && hostedDomain != null && !hostedDomain.isBlank();
    }

    /** The address normalised the way {@code AuthService.registerUser} normalises it. */
    public String normalizedEmail() {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}