-- Products: rename listings -> products (FK references in order_items, inspections,
-- site_visit_requests are automatically rewritten by PostgreSQL)
alter table listings rename to products;
alter index idx_listings_owner_id rename to idx_products_owner_id;
alter index idx_listings_status rename to idx_products_status;

alter table products drop constraint chk_listings_type;
alter table products drop constraint chk_listings_status;

alter table products add column unit varchar(30) not null default 'unit';
alter table products add column seller_type varchar(20) not null default 'SUPPLIER';
alter table products add column sector varchar(30) not null default 'AGRI_INPUTS';
alter table products add column reserved_quantity numeric(12, 2) not null default 0;
alter table products add column sold_quantity numeric(12, 2) not null default 0;
alter table products add column low_stock_threshold numeric(12, 2);
alter table products add column minimum_order_quantity numeric(12, 2) not null default 1;
alter table products add column category_id uuid;

update products
set sector = case when type = 'CROP' then 'FARM_PRODUCE' else 'AGRI_INPUTS' end,
    seller_type = case when type = 'CROP' then 'FARMER' else 'SUPPLIER' end;

alter table products drop column type;

alter table products add constraint chk_products_status
    check (status in ('ACTIVE', 'INACTIVE', 'HIDDEN', 'PENDING_APPROVAL'));
alter table products add constraint chk_products_seller_type check (seller_type in ('FARMER', 'SUPPLIER'));
alter table products add constraint chk_products_sector check (sector in ('FARM_PRODUCE', 'AGRI_INPUTS'));
alter table products add constraint chk_products_unit_price_positive check (unit_price > 0);
alter table products add constraint chk_products_available_quantity_non_negative check (available_quantity >= 0);

create table categories (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    name varchar(150) not null,
    sector varchar(30) not null,
    active boolean not null,
    constraint chk_categories_sector check (sector in ('FARM_PRODUCE', 'AGRI_INPUTS'))
);

alter table products add constraint fk_products_category foreign key (category_id) references categories (id);
create index idx_products_category_id on products (category_id);

create table product_images (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    product_id uuid not null,
    storage_key varchar(255) not null,
    file_name varchar(255) not null,
    content_type varchar(100) not null,
    size_bytes bigint not null,
    url varchar(500) not null,
    sort_order int not null,
    constraint uq_product_images_storage_key unique (storage_key),
    constraint fk_product_images_product foreign key (product_id) references products (id)
);
create index idx_product_images_product_id on product_images (product_id);

create table saved_products (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null,
    product_id uuid not null,
    constraint uq_saved_products_user_product unique (user_id, product_id),
    constraint fk_saved_products_user foreign key (user_id) references users (id),
    constraint fk_saved_products_product foreign key (product_id) references products (id)
);
create index idx_saved_products_user_id on saved_products (user_id);

-- Users: DRIVER role + verification status
alter table users drop constraint chk_users_role;
alter table users add constraint chk_users_role
    check (role in ('BUYER', 'SUPPLIER', 'FARMER', 'INSPECTOR', 'ADMIN', 'DRIVER'));
alter table users add column verification_status varchar(20) not null default 'UNVERIFIED';
alter table users add constraint chk_users_verification check (verification_status in ('UNVERIFIED', 'PENDING', 'VERIFIED'));

create table driver_profiles (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null,
    license_number varchar(100) not null,
    vehicle_type varchar(100) not null,
    vehicle_plate varchar(50) not null,
    availability varchar(20) not null,
    service_area varchar(150),
    constraint uq_driver_profiles_user unique (user_id),
    constraint fk_driver_profiles_user foreign key (user_id) references users (id),
    constraint chk_driver_profiles_availability check (availability in ('AVAILABLE', 'ON_JOB', 'OFF_DUTY'))
);

create table addresses (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null,
    label varchar(50) not null,
    address_line varchar(300) not null,
    latitude float8,
    longitude float8,
    is_default boolean not null,
    constraint fk_addresses_user foreign key (user_id) references users (id)
);
create index idx_addresses_user_id on addresses (user_id);

create table password_reset_tokens (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    user_id uuid not null,
    token varchar(150) not null,
    expires_at timestamptz not null,
    used boolean not null,
    constraint uq_password_reset_tokens_token unique (token),
    constraint fk_password_reset_tokens_user foreign key (user_id) references users (id)
);
create index idx_password_reset_tokens_user_id on password_reset_tokens (user_id);

-- Multi-seller customer orders + cart
create table customer_orders (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    buyer_id uuid not null,
    status varchar(20) not null,
    currency varchar(20) not null,
    shipping_address_id uuid,
    pickup_address_id uuid,
    constraint fk_customer_orders_buyer foreign key (buyer_id) references users (id),
    constraint fk_customer_orders_shipping_address foreign key (shipping_address_id) references addresses (id),
    constraint fk_customer_orders_pickup_address foreign key (pickup_address_id) references addresses (id),
    constraint chk_customer_orders_status check (status in ('NEW', 'PAID', 'CANCELLED'))
);
create index idx_customer_orders_buyer_id on customer_orders (buyer_id);

alter table orders add column customer_order_id uuid;

do $$
declare
    rec record;
    co_id uuid;
begin
    for rec in select id, buyer_id, currency from orders loop
        co_id := gen_random_uuid();
        insert into customer_orders (id, version, created_at, updated_at, buyer_id, status, currency, shipping_address_id, pickup_address_id)
        values (co_id, 0, now(), now(), rec.buyer_id, 'PAID', rec.currency, null, null);
        update orders set customer_order_id = co_id where id = rec.id;
    end loop;
end $$;

alter table orders alter column customer_order_id set not null;
alter table orders add constraint fk_orders_customer_order foreign key (customer_order_id) references customer_orders (id);

create table cart_items (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    buyer_id uuid not null,
    product_id uuid not null,
    quantity numeric(12, 2) not null,
    constraint fk_cart_items_buyer foreign key (buyer_id) references users (id),
    constraint fk_cart_items_product foreign key (product_id) references products (id),
    constraint chk_cart_items_quantity_positive check (quantity > 0)
);
create index idx_cart_items_buyer_id on cart_items (buyer_id);

-- Inspections: checklists + results + next inspection date
create table inspection_checklists (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    name varchar(150) not null,
    target_type varchar(30) not null,
    active boolean not null,
    constraint chk_inspection_checklists_target check (target_type in ('PRODUCT', 'USER', 'FARM'))
);

create table inspection_checklist_questions (
    id uuid primary key,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    checklist_id uuid not null,
    question varchar(500) not null,
    required boolean not null,
    sort_order int not null,
    constraint fk_checklist_questions_checklist foreign key (checklist_id) references inspection_checklists (id)
);
create index idx_checklist_questions_checklist_id on inspection_checklist_questions (checklist_id);

alter table inspections add column result varchar(30);
alter table inspections add column checklist_id uuid;
alter table inspections add column next_inspection_date timestamptz;
alter table inspections add constraint fk_inspections_checklist foreign key (checklist_id) references inspection_checklists (id);
alter table inspections add constraint chk_inspections_result check (result in ('APPROVED', 'REJECTED', 'CHANGES_REQUIRED'));
create index idx_inspections_checklist_id on inspections (checklist_id);