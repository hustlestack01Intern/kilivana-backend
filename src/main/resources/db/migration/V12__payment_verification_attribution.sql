-- Attribution for manual payment verification.
--
-- The only settlement path this deployment ships is a manual seller `verify`, because the
-- configured gateway is simulated and moves no money. That makes a seller's assertion the
-- only evidence that an order was paid, so the row has to record who made that assertion
-- and when. Without these columns a VERIFIED payment carries no trace of its author, and a
-- dispute about a paid order cannot be adjudicated from stored data at all.
--
-- Nullable: every existing row was verified before this column existed, and back-filling
-- them with a guessed actor would be a worse lie than a null.

alter table payments add column verified_by_user_id uuid;
alter table payments add column verified_at timestamptz;

alter table payments
    add constraint fk_payments_verified_by_user
        foreign key (verified_by_user_id) references users (id);

create index idx_payments_verified_by on payments (verified_by_user_id);