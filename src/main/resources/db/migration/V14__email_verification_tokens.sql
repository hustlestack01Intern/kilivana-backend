-- A mailbox-proof token that is NOT scoped to a user.
--
-- The entire Google sign-in design rests on one property: no `users` row exists until the
-- mailbox has been proven. That makes this table structurally different from
-- `password_reset_tokens` (V6:114-126), whose `user_id uuid not null` plus
-- `fk_password_reset_tokens_user foreign key (user_id) references users (id)` makes a
-- user-scoped token impossible here — there is no user to point at yet, by design. Reusing
-- that table would reintroduce exactly the row this flow refuses to create.
--
-- Why the token is keyed on the ADDRESS and not on a user: the caller presenting it has
-- proved control of a mailbox, not of an account. The address is stored already normalised
-- (`trim().toLowerCase(Locale.ROOT)`, matching AuthService.registerUser) so the row written
-- and the row read are guaranteed to be the same string and cannot differ by case or
-- surrounding whitespace.
--
-- token_hash is a SHA-256 hex digest (64 chars) from TokenService.hash, never the raw token:
-- a database dump must not be a set of working verification links. The unique constraint is
-- what makes a hash collision a startup error rather than two live tokens.
--
-- `used_at` is NULL until the token is spent. It is set by a conditional UPDATE
-- (`... where used_at is null`), not by an entity write, because two concurrent redemptions
-- must produce one success and one rejection rather than two successes with the version
-- column deciding the winner.

create table email_verification_tokens (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    token_hash varchar(64) not null,
    email varchar(120) not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    constraint uq_email_verification_tokens_hash unique (token_hash)
);

-- Supports the sweep of spent and expired rows. There is no such sweeper yet (see the
-- deferred note in the implementation report); the index exists so adding one is not a
-- migration, and so an ad-hoc cleanup does not scan the table.
create index idx_email_verification_tokens_expires_at on email_verification_tokens (expires_at);