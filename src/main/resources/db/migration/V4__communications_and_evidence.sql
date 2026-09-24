create table contact_messages (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    name varchar(100) not null,
    email varchar(120) not null,
    subject varchar(200) not null,
    body varchar(2000) not null,
    status varchar(20) not null,
    constraint chk_contact_messages_status check (status in ('OPEN', 'RESOLVED'))
);

create table site_visit_requests (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    farmer_id uuid not null,
    listing_id uuid not null,
    requested_at timestamptz not null,
    scheduled_at timestamptz,
    status varchar(20) not null,
    constraint fk_site_visit_requests_farmer foreign key (farmer_id) references users (id),
    constraint fk_site_visit_requests_listing foreign key (listing_id) references listings (id),
    constraint chk_site_visit_requests_status check (status in ('REQUESTED', 'SCHEDULED', 'COMPLETED', 'REJECTED'))
);

create table audit_reports (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    inspection_id uuid not null unique,
    report_number varchar(40) not null unique,
    summary varchar(2000) not null,
    recommendation varchar(500),
    issued_at timestamptz not null,
    constraint fk_audit_reports_inspection foreign key (inspection_id) references inspections (id)
);

create table visit_photos (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    inspection_id uuid not null,
    storage_key varchar(255) not null unique,
    file_name varchar(255) not null,
    content_type varchar(100) not null,
    size_bytes bigint not null,
    uploaded_at timestamptz not null,
    constraint fk_visit_photos_inspection foreign key (inspection_id) references inspections (id)
);

create table delivery_logs (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    delivery_id uuid not null,
    status varchar(20) not null,
    location varchar(200),
    note varchar(500),
    logged_at timestamptz not null,
    constraint fk_delivery_logs_delivery foreign key (delivery_id) references deliveries (id),
    constraint chk_delivery_logs_status check (status in ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'FAILED'))
);

create index idx_contact_messages_status on contact_messages (status);
create index idx_site_visit_requests_farmer_id on site_visit_requests (farmer_id);
create index idx_site_visit_requests_listing_id on site_visit_requests (listing_id);
create index idx_visit_photos_inspection_id on visit_photos (inspection_id);
create index idx_delivery_logs_delivery_id on delivery_logs (delivery_id);