package com.kilivana.users.domain;

/**
 * Lifecycle of a link between an account and an external identity.
 *
 * <p>{@code REVOKED} exists so a link can be withdrawn without deleting the row: deletion
 * would make "was this Google account ever linked here?" unanswerable, and the audit trail
 * is the only record of that once the row is gone.
 */
public enum UserIdentityStatus {

    ACTIVE,
    REVOKED
}