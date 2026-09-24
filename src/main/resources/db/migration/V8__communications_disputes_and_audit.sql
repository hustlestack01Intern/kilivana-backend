create table notifications (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    recipient_id uuid not null,
    type varchar(20) not null,
    title varchar(200) not null,
    body varchar(1000) not null,
    read boolean not null default false,
    read_at timestamptz,
    link varchar(250),
    constraint fk_notifications_recipient foreign key (recipient_id) references users (id),
    constraint chk_notifications_type check (type in ('ORDER', 'PAYMENT', 'INSPECTION', 'LOGISTICS', 'MESSAGE', 'SYSTEM'))
);

create index idx_notifications_recipient_id on notifications (recipient_id);
create index idx_notifications_recipient_read on notifications (recipient_id, read);
create index idx_notifications_created_at on notifications (created_at);

create table disputes (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    order_id uuid not null,
    raised_by_id uuid not null,
    status varchar(20) not null,
    subject varchar(200) not null,
    description varchar(2000) not null,
    resolution_note varchar(2000),
    resolved_by_id uuid,
    resolved_at timestamptz,
    constraint fk_disputes_order foreign key (order_id) references orders (id),
    constraint fk_disputes_raised_by foreign key (raised_by_id) references users (id),
    constraint fk_disputes_resolved_by foreign key (resolved_by_id) references users (id),
    constraint chk_disputes_status check (status in ('OPEN', 'ESCALATED', 'RESOLVED'))
);

create index idx_disputes_order_id on disputes (order_id);
create index idx_disputes_raised_by_id on disputes (raised_by_id);
create index idx_disputes_status on disputes (status);
create unique index uq_disputes_active_order on disputes (order_id) where status in ('OPEN', 'ESCALATED');

create table audit_logs (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    actor_id uuid,
    action varchar(100) not null,
    entity_type varchar(100) not null,
    entity_id uuid,
    details varchar(2000),
    ip_address varchar(45),
    occurred_at timestamptz not null,
    constraint fk_audit_logs_actor foreign key (actor_id) references users (id)
);

create index idx_audit_logs_occurred_at on audit_logs (occurred_at);
create index idx_audit_logs_actor_id on audit_logs (actor_id);
create index idx_audit_logs_entity on audit_logs (entity_type, entity_id);
