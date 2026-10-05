package com.kilivana.auth.google;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rule that decides whether Google's word about an address is worth acting on.
 *
 * <p>This is the boundary between "Google told us an email" and "this mailbox is proven". It is
 * worth a test file of its own because it is four lines of logic whose failure mode is silent
 * and total: get {@code email_verified} wrong in the permissive direction and every attacker
 * with any Google account can claim any address on the platform.
 */
class GoogleIdentityClaimsTest {

    @Nested
    @DisplayName("an address is authoritative")
    class Authoritative {

        @Test
        void gmailIsAlwaysAuthoritative() {
            assertThat(claims("person@gmail.com", false, null).authoritative()).isTrue();
        }

        @Test
        void gmailIsAuthoritativeEvenWhenGoogleSaysItIsNotVerified() {
            // Google's own rule, and deliberately not tightened here. email_verified is false
            // for Gmail because Google has no reason to set it, not because the address is
            // doubtful; treating that as unproven would send every Gmail signup to the mail
            // round-trip that exists for addresses Google cannot speak for.
            assertThat(claims("Person@GMail.com", false, null).authoritative()).isTrue();
        }

        @Test
        void aWorkspaceAddressIsAuthoritativeWhenVerifiedAndHosted() {
            assertThat(claims("inspector@example.org", true, "example.org").authoritative()).isTrue();
        }

        @Test
        void aWorkspaceAddressIsNotAuthoritativeWithoutTheHostedDomain() {
            assertThat(claims("inspector@example.org", true, null).authoritative()).isFalse();
        }

        @Test
        void aBlankHostedDomainDoesNotCount() {
            assertThat(claims("inspector@example.org", true, "   ").authoritative()).isFalse();
        }
    }

    @Nested
    @DisplayName("an address is not authoritative")
    class NotAuthoritative {

        @Test
        void anUnverifiedThirdPartyAddressIsNotAuthoritative() {
            // The case the whole verification-email branch exists for.
            assertThat(claims("victim@example.com", false, null).authoritative()).isFalse();
        }

        @Test
        void aVerifiedThirdPartyAddressIsStillNotAuthoritative() {
            // Verified by Google but not on a hosted domain. Google will say this about an
            // address whose domain it does not administer, so this is the shape an attacker
            // arranges by registering a domain they control and adding it to a Google account.
            assertThat(claims("attacker@evil.example", true, null).authoritative()).isFalse();
        }

        @Test
        void anAbsentEmailIsNotAuthoritative() {
            assertThat(claims(null, true, "example.org").authoritative()).isFalse();
        }

        @Test
        void aBlankEmailIsNotAuthoritative() {
            assertThat(claims("   ", true, "example.org").authoritative()).isFalse();
        }
    }

    @Test
    @DisplayName("normalisedEmail lowercases and trims, and leaves an absent address absent")
    void normalisedEmail() {
        assertThat(claims("  Person@Example.ORG ", true, null).normalizedEmail()).isEqualTo("person@example.org");
        assertThat(claims(null, true, null).normalizedEmail()).isNull();
    }

    private static GoogleIdentityClaims claims(String email, boolean emailVerified, String hostedDomain) {
        return new GoogleIdentityClaims("subject-1", email, emailVerified, hostedDomain, "A Person");
    }
}