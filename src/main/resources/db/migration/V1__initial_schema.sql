create table users (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    email varchar(120) not null,
    password_hash varchar(120) not null,
    full_name varchar(100) not null,
    phone_number varchar(20) not null,
    role varchar(20) not null,
    status varchar(20) not null,
    constraint uq_users_email unique (email),
    constraint chk_users_role check (role in ('BUYER', 'SUPPLIER', 'FARMER', 'INSPECTOR')),
    constraint chk_users_status check (status in ('ACTIVE', 'SUSPENDED'))
);

create table buyer_profiles (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null unique,
    constraint fk_buyer_profiles_user foreign key (user_id) references users (id)
);

create table supplier_profiles (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null unique,
    business_name varchar(150) not null,
    constraint fk_supplier_profiles_user foreign key (user_id) references users (id)
);

create table farmer_profiles (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null unique,
    farm_name varchar(150) not null,
    farm_location varchar(200) not null,
    constraint fk_farmer_profiles_user foreign key (user_id) references users (id)
);

create table inspector_profiles (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null unique,
    employee_code varchar(100) not null,
    constraint fk_inspector_profiles_user foreign key (user_id) references users (id)
);

create table refresh_tokens (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null,
    token_value varchar(150) not null,
    expires_at timestamptz not null,
    revoked boolean not null,
    constraint uq_refresh_tokens_value unique (token_value),
    constraint fk_refresh_tokens_user foreign key (user_id) references users (id)
);

create table listings (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    owner_id uuid not null,
    type varchar(20) not null,
    status varchar(20) not null,
    title varchar(150) not null,
    description varchar(1000) not null,
    unit_price numeric(12, 2) not null,
    available_quantity numeric(12, 2) not null,
    constraint fk_listings_owner foreign key (owner_id) references users (id),
    constraint chk_listings_type check (type in ('CROP', 'PRODUCT')),
    constraint chk_listings_status check (status in ('ACTIVE', 'INACTIVE')),
    constraint chk_listings_unit_price_positive check (unit_price > 0),
    constraint chk_listings_available_quantity_non_negative check (available_quantity >= 0)
);

create table orders (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    buyer_id uuid not null,
    seller_id uuid not null,
    status varchar(20) not null,
    total_amount numeric(12, 2) not null,
    currency varchar(20) not null,
    constraint fk_orders_buyer foreign key (buyer_id) references users (id),
    constraint fk_orders_seller foreign key (seller_id) references users (id),
    constraint chk_orders_status check (status in ('PLACED', 'CONFIRMED', 'IN_PROGRESS', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'REJECTED')),
    constraint chk_orders_total_amount_positive check (total_amount > 0)
);

create table order_items (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    order_id uuid not null,
    listing_id uuid not null,
    quantity numeric(12, 2) not null,
    unit_price numeric(12, 2) not null,
    line_total numeric(12, 2) not null,
    constraint fk_order_items_order foreign key (order_id) references orders (id),
    constraint fk_order_items_listing foreign key (listing_id) references listings (id),
    constraint chk_order_items_quantity_positive check (quantity > 0),
    constraint chk_order_items_unit_price_positive check (unit_price > 0),
    constraint chk_order_items_line_total_positive check (line_total > 0)
);

create table payments (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    order_id uuid not null,
    status varchar(20) not null,
    provider varchar(40) not null,
    amount numeric(12, 2) not null,
    idempotency_key varchar(100) not null,
    constraint uq_payments_idempotency_key unique (idempotency_key),
    constraint fk_payments_order foreign key (order_id) references orders (id),
    constraint chk_payments_status check (status in ('PENDING', 'VERIFIED', 'FAILED', 'REFUNDED')),
    constraint chk_payments_amount_positive check (amount > 0)
);

create index idx_refresh_tokens_user_id on refresh_tokens (user_id);
create index idx_listings_owner_id on listings (owner_id);
create index idx_listings_status on listings (status);
create index idx_orders_buyer_id on orders (buyer_id);
create index idx_orders_seller_id on orders (seller_id);
create index idx_order_items_order_id on order_items (order_id);
create index idx_payments_order_id on payments (order_id);
