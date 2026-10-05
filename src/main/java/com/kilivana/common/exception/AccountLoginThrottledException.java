package com.kilivana.common.exception;

/**
 * The account named in a login attempt has spent its own attempt budget for the current window.
 *
 * <p>Deliberately not a {@link com.kilivana.common.exception.AuthenticationFailedException}: it
 * is not a credential outcome, and answering it 401 would let a caller keep guessing. It is also
 * deliberately identical for an account that exists and one that does not, because the limit is
 * consumed before the account is ever looked up. A caller can therefore tell that <em>somebody</em>
 * has been guessing that address; it can never tell whether that somebody is a registered user,
 * which is the only question this exception must never answer.
 */
public class AccountLoginThrottledException extends RuntimeException {

    private static final String MESSAGE = "Too many login attempts for this account. Please retry later.";

    private final long retryAfterSeconds;

    public AccountLoginThrottledException(long retryAfterSeconds) {
        super(MESSAGE);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}