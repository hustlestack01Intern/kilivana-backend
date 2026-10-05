package com.kilivana.users.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.kilivana.common.exception.InvalidAccountDataException;
import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;

/** Canonical account identifiers shared by registration, profile updates and login. */
public final class AccountIdentity {
    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();

    private AccountIdentity() { }

    public static String username(String value) {
        String canonical = required(value, "username", 30).toLowerCase(Locale.ROOT);
        if (!canonical.matches("[a-z][a-z0-9_]{2,29}")) {
            throw new InvalidAccountDataException("username must match [a-z][a-z0-9_]{2,29}: 3-30 characters, starting with an ASCII letter");
        }
        return canonical;
    }

    public static String region(String value) {
        return required(value, "region", 200);
    }

    /**
     * A username for an account that never chose one — a Google onboarding, an admin
     * bootstrap — as opposed to {@link #username(String)}, which validates a chosen one.
     *
     * <p>{@code u_} plus the full unsigned UUID in base 36: at most 25 digits, so 27 of the
     * column's 30 characters, and it satisfies V11's {@code chk_users_username} regex without
     * a second check here because 'u' is the leading ASCII letter and base 36 digits plus the
     * underscore prefix are all the rest of the character class. 36^25 dwarfs 2^128, so a
     * collision is not a thing to plan around — but {@code uq_users_username} is enforced by
     * the database, not by this method, and the caller is what turns a violation into a
     * retry.
     *
     * <p>Base36 of the raw bits rather than the UUID's own hyphenated text, because the text
     * carries hyphens that the regex forbids and stripping them is a step this method would
     * have to justify. This is the same construction V11__account_identity used to backfill
     * existing rows, deliberately, so generated names and migrated names cannot be told apart
     * by shape.
     */
    public static String generatedUsername() {
        String suffix = new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString(36);
        return "u_" + suffix;
    }

    public static String required(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw new InvalidAccountDataException(field + " must be nonblank and at most " + maximum + " characters");
        }
        return value.trim();
    }

    public static String phone(String value) {
        String input = required(value, "phoneNumber", 40);
        if (!input.matches("\\+?[0-9 ()-]+")) {
            throw new InvalidAccountDataException("phoneNumber must be a valid Kenyan number without extensions");
        }
        try {
            var parsed = PHONES.parse(input, "KE");
            if (parsed.hasExtension() || !PHONES.isValidNumberForRegion(parsed, "KE")) {
                throw new InvalidAccountDataException("phoneNumber must be a valid Kenyan number");
            }
            return PHONES.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw new InvalidAccountDataException("phoneNumber must be a valid Kenyan number", exception);
        }
    }
}
