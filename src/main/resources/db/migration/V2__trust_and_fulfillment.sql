create table badges (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    code varchar(100) not null,
    name varchar(120) not null,
    description varchar(500) not null,
    icon varchar(300) not null,
    constraint uq_badges_code unique (code)
);

create table user_badges (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null,
    badge_id uuid not null,
    awarded_by uuid not null,
    note varchar(500) not null,
    awarded_at timestamptz not null,
    constraint uq_user_badges_user_badge unique (user_id, badge_id),
    constraint fk_user_badges_user foreign key (user_id) references users (id),
    constraint fk_user_badges_badge foreign key (badge_id) references badges (id),
    constraint fk_user_badges_awarded_by foreign key (awarded_by) references users (id)
);

create table inspections (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    inspector_id uuid not null,
    listing_id uuid not null,
    status varchar(20) not null,
    rating integer,
    findings varchar(2000),
    scheduled_at timestamptz not null,
    performed_at timestamptz,
    constraint fk_inspections_inspector foreign key (inspector_id) references users (id),
    constraint fk_inspections_listing foreign key (listing_id) references listings (id),
    constraint chk_inspections_status check (status in ('SCHEDULED', 'IN_PROGRESS', 'PASSED', 'FAILED')),
    constraint chk_inspections_rating check (rating is null or (rating between 1 and 5))
);

create table deliveries (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    order_id uuid not null,
    status varchar(20) not null,
    carrier_name varchar(150) not null,
    tracking_number varchar(150) not null,
    notes varchar(500),
    estimated_arrival timestamptz not null,
    delivered_at timestamptz,
    constraint uq_deliveries_order unique (order_id),
    constraint fk_deliveries_order foreign key (order_id) references orders (id),
    constraint chk_deliveries_status check (status in ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'FAILED'))
);

insert into badges (id, version, created_at, updated_at, code, name, description, icon)
values
    (gen_random_uuid(), 0, now(), now(), 'QUALITY_HARVEST', 'Quality Harvest',
     'Produce that passed an inspector''s quality assessment', 'trophy'),
    (gen_random_uuid(), 0, now(), now(), 'TRUSTED_SUPPLIER', 'Trusted Supplier',
     'Supplier recognised for reliable and consistent supply', 'shield'),
    (gen_random_uuid(), 0, now(), now(), 'CERTIFIED_FARM', 'Certified Farm',
     'Farm certified after an on-site inspection', 'leaf'),
    (gen_random_uuid(), 0, now(), now(), 'RELIABLE_BUYER', 'Reliable Buyer',
     'Buyer with a strong record of honoured orders', 'handshake'),
    (gen_random_uuid(), 0, now(), now(), 'COMMUNITY_PARTNER', 'Community Partner',
     'Recognised contributor to the Kilivana community', 'star');

create index idx_user_badges_user_id on user_badges (user_id);
create index idx_inspections_inspector_id on inspections (inspector_id);
create index idx_inspections_listing_id on inspections (listing_id);
create index idx_deliveries_order_id on deliveries (order_id);