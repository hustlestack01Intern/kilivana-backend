package com.kilivana.users.service;

import java.util.Locale;

/**
 * The canonical form an email address must take before it is hashed into an account login
 * throttle key.
 *
 * <p>The account login budget is keyed on this value rather than on the address as typed, so the
 * question this class answers is narrow and load-bearing: <em>which distinct strings must collapse
 * into one key, and which must not.</em> {@code AuthService#login} resolves the account with
 * {@code findByEmailIgnoreCase}, which is {@code upper(email) = upper(:typed)} under the database
 * collation. So the database's idea of "the same address" is the spec, not Java's — and a key that
 * disagrees with it gives one account several budgets.
 *
 * <p>Two normalisations are layered deliberately:
 *
 * <ol>
 *   <li>Five code points are mapped explicitly, because {@link String#toLowerCase(Locale.ROOT)}
 *       maps none of them while glibc/en_US.utf8's {@code upper()} folds each onto a single
 *       canonical form: {@code ı U+0131 DOTLESS I} and {@code İ U+0130 LATIN CAPITAL LETTER I
 *       WITH DOT ABOVE} onto {@code i}, {@code ſ U+017F LATIN SMALL LETTER LONG S} onto
 *       {@code s}, {@code ς U+03C2 GREEK SMALL LETTER FINAL SIGMA} onto {@code σ}, and
 *       {@code µ U+00B5 MICRO SIGN} onto {@code μ}. Without these substitutions a Turkish address
 *       and its ASCII twin reach the same account on two separate budgets. Each of the five was
 *       measured against {@code upper()} on the pinned image, and {@link #normalize(String)} is
 *       held to that list deliberately: a fold the collation does not perform merges two genuinely
 *       distinct accounts onto one budget, which locks out a real user for the length of the
 *       window because someone else guessed their address.
 *   <li>{@link String#toLowerCase(Locale.ROOT)}, never {@code toLowerCase()} without a locale —
 *       the default locale lowercases {@code I} to {@code i} in Turkish and leaves it alone
 *       everywhere else, so an unpinned lowercasing makes the throttle key depend on the server's
 *       {@code LANG}.
 * </ol>
 *
 * <p>{@link Normalizer} is deliberately <em>not</em> used. NFKC folds compatibility forms, so
 * {@code ａｄｍｉｎ} and {@code admin} would share one key — but {@code upper()} under the pinned
 * collation keeps them distinct, making that a self-inflicted lockout rather than defence in depth.
 * A compatibility form therefore costs an attacker an extra budget, which is the cheaper of the
 * two mistakes.
 *
 * <p>This is a defence in depth and not a replacement for the collation pin in
 * {@code docker-compose.yml}. The two must agree; when they do, the throttle key is exactly the
 * set of strings Postgres considers equal. When they disagree — a database created with
 * {@code --lc-collate=C} keeps the {@code postgres_data} volume it was initialised with, so the pin
 * is not retroactive — {@code login} keys on the <em>stored</em> address, which makes this class
 * apply to exactly one spelling per account and the budget still hold.
 */
public final class AccountLoginThrottleKeyNormalizer {

    /** U+0131 DOTLESS I → ASCII i. Glibc/en_US.utf8 folds this; {@code toLowerCase} does not. */
    private static final char DOTLESS_I = 'ı';

    /** U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE → ASCII i. Folds to i + combining dot. */
    private static final char DOTTED_CAPITAL_I = 'İ';

    /** U+017F LATIN SMALL LETTER LONG S → ASCII s. Folds to uppercase S as well. */
    private static final char LATIN_SMALL_LETTER_LONG_S = 'ſ';

    /** U+03C2 GREEK SMALL LETTER FINAL SIGMA → U+03C3 sigma. {@code upper()} folds these. */
    private static final char GREEK_FINAL_SIGMA = 'ς';

    /** U+00B5 MICRO SIGN → U+03BC greek small letter mu. {@code upper()} folds these. */
    private static final char MICRO_SIGN = 'µ';

    private AccountLoginThrottleKeyNormalizer() { }

    /**
     * Canonicalises an address for use as a throttle key.
     *
     * @param value the stored or typed address; already trimmed by the caller
     * @return the canonical form, or {@code null} for {@code null}
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder folded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == DOTLESS_I || current == DOTTED_CAPITAL_I) {
                folded.append('i');
            } else if (current == LATIN_SMALL_LETTER_LONG_S) {
                folded.append('s');
            } else if (current == GREEK_FINAL_SIGMA) {
                folded.append('σ');
            } else if (current == MICRO_SIGN) {
                folded.append('μ');
            } else {
                folded.append(current);
            }
        }
        return folded.toString().toLowerCase(Locale.ROOT);
    }
}