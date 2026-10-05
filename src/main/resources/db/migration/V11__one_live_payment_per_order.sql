-- One live (PENDING) payment per order, enforced by the database.
--
-- PaymentService.create rejects a second PENDING payment for an order, but that check is a
-- read followed by a write, so two concurrent requests with different idempotency keys can
-- both observe "none pending" and both insert. Only one of the four credential-era guarantees
-- in the service layer survives concurrency unless the constraint is here.
--
-- The index is partial on status = 'PENDING' rather than unique on order_id, deliberately:
-- an order legitimately accumulates history — a payment that FAILED, and one that was
-- VERIFIED and later REFUNDED — and a full unique index on order_id would forbid that history
-- outright. Only *concurrent live attempts* are the thing worth forbidding.
--
-- Verified payments are untouched, so this cannot reject an existing legitimate row: the
-- constraint only fires on a second simultaneous PENDING for the same order.

-- RECONCILIATION. This MUTATES HISTORICAL PAYMENT ROWS. Read this before changing it.
--
-- Why it exists: the index below aborts the whole migration if any order already holds two
-- PENDING payments, and that race was possible before this constraint existed. Flyway runs at
-- startup, so an abort here is not a degraded deploy — it is a platform that will not boot,
-- and the operator gets a bare unique-violation naming no order and offering no next step. The
-- reconciliation converts that dead end into a printed count.
--
-- The survivor rule: keep the NEWEST PENDING payment per order (created_at desc, id desc as
-- the tiebreaker) and transition every other PENDING for that order to FAILED. Newest wins
-- because a later attempt at the same order is the one a customer retrying a payment produced,
-- and it is the attempt the rest of the system would treat as current; an older abandoned
-- attempt has no reason to stay live. id desc is the tiebreaker because created_at alone is not
-- unique — BaseEntity stamps it with OffsetDateTime.now() at @PrePersist — and without a
-- tiebreaker two runs over the same data could keep different rows, so a rollback would be
-- unreproducible. id is the primary key, hence unique, hence a total order; desc rather than asc
-- because the id carries no recency information either way — what matters is that the direction
-- is fixed, and a test pins it so it cannot drift silently.
--
-- Only PENDING rows are ranked. A PENDING that is older than a VERIFIED sibling is still the
-- live one and is kept; the constraint counts live rows, not recent rows.
--
-- Only `status` is written: no timestamp and no version is touched, so a migrated row still
-- carries the timestamps the application gave it. The consequence, stated so nobody reads it as
-- a bug later, is that an application instance still serving traffic while this migration runs
-- can overwrite a transitioned row back to PENDING from a version it read before the migration
-- (the row lock only serialises the two writes, it does not invalidate the stale version). The
-- index still holds in that window — at most one PENDING per order is a database-enforced fact,
-- not a convention this migration relies on — so the worst case is one FAILED row reading PENDING
-- again, which an operator can see. Bumping `version` here would close the window at the cost of
-- an OptimisticLockingFailureException for any in-flight writer; that is a deliberate choice for
-- whoever finds this in a database that does need it.
--
-- FAILED is a legal destination: V1__initial_schema.sql's chk_payments_status admits it and
-- PaymentStatus.FAILED is an existing enum constant written as the literal string by
-- @Enumerated(EnumType.STRING), which is also why the partial predicate below can compare
-- against 'PENDING' as text. No row is deleted, no amount is changed, and no row that is not
-- PENDING is written at all.
--
-- Idempotent by construction: row_number() yields 1 to one row per order and the UPDATE only
-- touches rn > 1, so on already-clean data it matches nothing, reports 0, and errors never.
-- `if not exists` below is the same property for the index, so the whole script can be re-run
-- against a database that already has it.
--
-- For an operator reading the boot log: a non-zero count here means this database HAD duplicate
-- live payments — i.e. a real double-submit reached the payments table before this index
-- existed. Those orders' losers now read FAILED. Nothing was charged by that: the gateway is
-- simulated, so at worst a buyer saw two attempts and one of them was recorded as failed. If a
-- count appears and someone disputes an order, those are the orders to look at first.
--
-- Flyway logs the NOTICE at INFO as "DB: V11: reconciled N ..." while applying this version, so
-- the count is in the application's startup log rather than only in the database's.
do $$
declare
    reconciled integer;
begin
    with ranked as (
        select id,
               row_number() over (partition by order_id order by created_at desc, id desc) as rn
        from payments
        where status = 'PENDING'
    )
    update payments p
       set status = 'FAILED'
      from ranked r
     where p.id = r.id
       and r.rn > 1;

    get diagnostics reconciled = row_count;

    raise notice 'V11: reconciled % duplicate PENDING payment row(s) to FAILED, keeping the newest per order. '
                 'A non-zero count means duplicate live payments had reached this database before V11 existed.',
                 reconciled;
end
$$;

create unique index if not exists uq_payments_one_pending_per_order
    on payments (order_id)
    where status = 'PENDING';
