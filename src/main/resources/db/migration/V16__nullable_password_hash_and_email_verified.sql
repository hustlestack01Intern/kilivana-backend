-- A Google-only account has no password, so `password_hash` can no longer be NOT NULL.
--
-- This is deliberately NOT a separate Google-user table and NOT an empty-string sentinel.
-- An empty string is still a value that bcrypt would be asked to match against, and it makes
-- "this account has no password" a runtime surprise rather than a schema fact. NULL means
-- "there is no password", and AuthService.login is required to spend a dummy bcrypt
-- comparison on a NULL-hash account so the two cases cost the same wall-clock time.
--
-- `alter column ... drop not null` only widens the domain: no existing row changes, and every
-- row written before this migration still carries a real hash. Rolling back is therefore not
-- possible without first refusing NULL — leave the constraint off rather than assume.
--
-- email_verified_at records that the ADDRESS was proven, and is NEVER backfilled. Every
-- account that existed before this migration got there through a password flow that never
-- asked for proof, so backfilling `now()` would assert something untrue about all of them and
-- NULL is the honest value for each. Consumers gate on `User.hasProvenEmail()`, which is
-- isNotNull().
--
-- This is NOT `verification_status`. That column is the INSPECTOR approval state:
-- AuthenticatedUser.isVerifiedInspector reads it and returns EMPTY authorities for an
-- inspector who is not VERIFIED, which nine call sites consume. Overloading it would either
-- hand inspector powers to any mailbox-confirmed signup or strip them from an
-- admin-approved inspector whose address was never re-proven. Two columns, two meanings.

alter table users alter column password_hash drop not null;

alter table users add column email_verified_at timestamptz;