-- Links a Kilivana account to a Google identity.
--
-- Account resolution is by Google's `sub` and by nothing else — never by the email claim,
-- because an unverified email claim is attacker-chosen. `sub` is stable for the life of the
-- Google account; the email it carries can change, and a row keyed on the email would let a
-- rename silently hand one person's account to another.
--
-- uq_user_identities_provider_subject is the whole point of this table. "One Google account
-- maps to one Kilivana account" is expressed as a storage invariant rather than as a
-- read-then-write check in a service, so two concurrent first-time sign-ins for one `sub` are
-- resolved by Postgres — one commits, the other aborts — instead of both passing a race and
-- creating two accounts. `subject` is an opaque string, never numeric: it is documented as a
-- decimal today, and a numeric column would make a future non-numeric subject a migration
-- rather than a lookup failure.
--
-- provider and status are constrained rather than left free text so an unrecognised value is
-- a write that cannot happen. `provider in ('GOOGLE')` today; the column exists so a second
-- provider is data, not a migration.

create table user_identities (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null references users (id) on delete cascade,
    provider varchar(32) not null,
    subject varchar(255) not null,
    -- The address Google asserted at link time, for operator surfacing only. It is never a
    -- lookup key and never authorises anything: `users.email` is the address of record.
    email_at_link_time varchar(120),
    -- What Google's token claimed at link time. Evidence, not authority — the authority
    -- rule that decides whether the address had to be proven is applied at sign-in and is
    -- not recoverable from this column afterwards.
    provider_email_verified boolean not null default false,
    -- Google's Workspace hosted domain. Proves DOMAIN membership, never employment, so it
    -- can raise an approval signal and can never grant a role by itself.
    hosted_domain varchar(255),
    inspector_domain_approved boolean not null default false,
    status varchar(20) not null default 'ACTIVE',
    linked_at timestamptz not null default now(),
    -- Refreshed on every successful re-authentication, so "when did this identity last prove
    -- it still controls the Google account" is answerable without reading the audit log.
    last_verified_at timestamptz,
    constraint uq_user_identities_provider_subject unique (provider, subject),
    constraint chk_user_identities_provider check (provider in ('GOOGLE')),
    constraint chk_user_identities_status check (status in ('ACTIVE', 'REVOKED'))
);

-- Every read of an identity is by user (account-link conflict check, admin surfacing); the
-- lookup by (provider, subject) is served by the unique constraint's own index.
create index idx_user_identities_user_id on user_identities (user_id);