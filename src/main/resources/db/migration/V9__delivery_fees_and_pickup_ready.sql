-- Delivery fees on seller sub-orders + READY_FOR_PICKUP order status.

alter table orders add column subtotal numeric(12, 2) not null default 0;
alter table orders add column delivery_fee numeric(12, 2) not null default 0;

do $$
begin
    update orders
       set subtotal = total_amount
     where subtotal = 0;
end $$;

alter table orders alter column subtotal drop default;
alter table orders alter column delivery_fee drop default;

alter table orders drop constraint chk_orders_status;
alter table orders add constraint chk_orders_status check (status in (
    'PLACED', 'CONFIRMED', 'READY_FOR_PICKUP', 'IN_PROGRESS', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'REJECTED'
));

alter table orders drop constraint chk_orders_total_amount_positive;
alter table orders add constraint chk_orders_total_amount_positive check (total_amount > 0);
alter table orders add constraint chk_orders_subtotal_non_negative check (subtotal >= 0);
alter table orders add constraint chk_orders_delivery_fee_non_negative check (delivery_fee >= 0);

create index idx_orders_status on orders (status);