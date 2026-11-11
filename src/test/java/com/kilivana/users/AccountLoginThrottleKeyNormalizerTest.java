package com.kilivana.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.kilivana.users.service.AccountLoginThrottleKeyNormalizer;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The set of strings that must share one account login throttle key.
 *
 * <p>Every case here is a spelling {@code AuthService.login} resolves to the <em>same</em> account
 * — the repository query is {@code upper(email) = upper(:typed)} — and which therefore must not
 * receive its own budget. Each test names the spelling that motivated it, because "these two
 * strings are equal" is only interesting when it is surprising.
 */
class AccountLoginThrottleKeyNormalizerTest {

    @Test
    @DisplayName("a dotless-i spelling folds onto the ASCII address, which lowercase alone does not do")
    void dotlessIFoldsOntoAscii() {
        // "admın@example.com" with U+0131. toLowerCase(ROOT) leaves U+0131 as U+0131, so the
        // account gets a second budget unless the code point is mapped explicitly.
        assertThat(AccountLoginThrottleKeyNormalizer.normalize("adm\u0131n@example.com"))
                .isEqualTo(AccountLoginThrottleKeyNormalizer.normalize("admin@example.com"));
    }

    @Test
    @DisplayName("a dotted capital-I spelling folds onto the ASCII address")
    void dottedCapitalIFoldsOntoAscii() {
        assertThat(AccountLoginThrottleKeyNormalizer.normalize("ADM\u0130N@EXAMPLE.COM"))
                .isEqualTo(AccountLoginThrottleKeyNormalizer.normalize("admin@example.com"));
    }

    @Test
    @DisplayName("a long-s spelling folds onto ASCII s, in the domain as well as the local part")
    void longSFoldsOntoAscii() {
        // "boss@assist.com" with U+017F in place of every s — two in the domain and one in the
        // local part, so neither half of the address is left unexercised. The domain needs its own
        // s's because "example" has none, and a local-part-only fixture would let a normalizer that
        // ignored the domain pass.
        assertThat(AccountLoginThrottleKeyNormalizer.normalize("bo\u017Fs@assi\u017Ft.com"))
                .isEqualTo(AccountLoginThrottleKeyNormalizer.normalize("boss@assist.com"));
    }

    @Test
    @DisplayName("a Kelvin sign folds onto ASCII K through NFKC, without a fourth special case")
    void kelvinSignFoldsOntoAscii() {
        assertThat(AccountLoginThrottleKeyNormalizer.normalize("\u212A"))
                .isEqualTo(AccountLoginThrottleKeyNormalizer.normalize("k"));
        assertThat(AccountLoginThrottleKeyNormalizer.normalize("mi\u212A"))
                .isEqualTo(AccountLoginThrottleKeyNormalizer.normalize("mik"));
    }

    @Test
    @DisplayName("a fullwidth compatibility spelling stays distinct, because the collation keeps it distinct")
    void fullwidthStaysDistinct() {
        // U+FF41 FULLWIDTH LATIN SMALL LETTER A and friends are what NFKC would fold to ASCII,
        // and this normaliser deliberately does not run NFKC: en_US.utf8's upper() maps the
        // fullwidth letters to fullwidth capitals, not to ASCII, so the two spellings resolve to
        // different accounts. Folding them here would merge two customers onto one throttle
        // budget and let either lock the other out. Asserted as distinct so a future "helpful"
        // NFKC addition fails here instead of in production.
        assertThat(AccountLoginThrottleKeyNormalizer.normalize("\uFF41\uFF44\uFF4D\uFF49\uFF4E@example.com"))
                .isNotEqualTo(AccountLoginThrottleKeyNormalizer.normalize("admin@example.com"));
    }

    @Test
    @DisplayName("normalisation does not depend on the JVM default locale")
    void normalisationIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            // In tr, an unpinned toLowerCase() leaves ASCII I alone rather than folding it to i,
            // so the same account would key differently on a Turkish server than on any other.
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(AccountLoginThrottleKeyNormalizer.normalize("ADMIN@EXAMPLE.COM"))
                    .isEqualTo("admin@example.com");
            assertThat(AccountLoginThrottleKeyNormalizer.normalize("adm\u0131n@example.com"))
                    .isEqualTo("admin@example.com");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("null is passed through, so an absent key cannot become the string \"null\"")
    void nullPassesThrough() {
        assertThat(AccountLoginThrottleKeyNormalizer.normalize(null)).isNull();
    }

    @Test
    @DisplayName("two genuinely different addresses do not collapse onto one key")
    void distinctAddressesStayDistinct() {
        // The complement of every test above: over-normalising would merge two customers into one
        // budget and let either lock the other out, which is a availability bug, not just a
        // security one.
        assertThat(AccountLoginThrottleKeyNormalizer.normalize("admin@example.com"))
                .isNotEqualTo(AccountLoginThrottleKeyNormalizer.normalize("admin2@example.com"));
        assertThat(AccountLoginThrottleKeyNormalizer.normalize("admin@example.com"))
                .isNotEqualTo(AccountLoginThrottleKeyNormalizer.normalize("admin@example.co"));
    }
}