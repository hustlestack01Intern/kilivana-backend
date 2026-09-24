alter table payments add column gateway_reference varchar(150);
alter table payments add column external_id varchar(150);

create index idx_payments_external_id on payments (external_id);