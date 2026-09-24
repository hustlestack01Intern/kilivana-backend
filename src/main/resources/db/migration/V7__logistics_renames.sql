-- Logistics rework: deliveries -> logistics_jobs, delivery_logs -> tracking_events

alter table deliveries rename to logistics_jobs;
alter index idx_deliveries_order_id rename to idx_logistics_jobs_order_id;
alter table logistics_jobs rename constraint uq_deliveries_order to uq_logistics_jobs_order;
alter table logistics_jobs rename constraint fk_deliveries_order to fk_logistics_jobs_order;

-- Normalise legacy statuses into the new lifecycle before swapping the constraint.
alter table logistics_jobs drop constraint chk_deliveries_status;
update logistics_jobs set status = 'PENDING_ACCEPTANCE' where status = 'ASSIGNED';

alter table logistics_jobs alter column status type varchar(30);
alter table logistics_jobs
    add column driver_id uuid,
    add column pickup_address_id uuid,
    add column delivery_address_id uuid,
    add column pod_storage_key varchar(255),
    add column pod_file_name varchar(255),
    add column pod_content_type varchar(100),
    add column pod_submitted_at timestamptz,
    add column delivered_to varchar(150);

alter table logistics_jobs drop column carrier_name;
alter table logistics_jobs drop column tracking_number;

alter table logistics_jobs
    add constraint fk_logistics_jobs_driver foreign key (driver_id) references users (id),
    add constraint fk_logistics_jobs_pickup foreign key (pickup_address_id) references addresses (id),
    add constraint fk_logistics_jobs_delivery foreign key (delivery_address_id) references addresses (id);

alter table logistics_jobs
    add constraint chk_logistics_jobs_status check (status in (
        'PENDING_ACCEPTANCE', 'ACCEPTED', 'AT_PICKUP', 'PICKED_UP',
        'IN_TRANSIT', 'DELIVERED', 'FAILED', 'CANCELLED'));

create index idx_logistics_jobs_driver_id on logistics_jobs (driver_id);
create index idx_logistics_jobs_status on logistics_jobs (status);

alter table delivery_logs rename to tracking_events;
alter table tracking_events rename column delivery_id to job_id;
alter table tracking_events rename column location to location_name;
alter index idx_delivery_logs_delivery_id rename to idx_tracking_events_job_id;
alter table tracking_events rename constraint fk_delivery_logs_delivery to fk_tracking_events_job;
alter table tracking_events rename constraint chk_delivery_logs_status to chk_tracking_events_status;
alter table tracking_events drop constraint chk_tracking_events_status;

update tracking_events set status = 'PENDING_ACCEPTANCE' where status = 'ASSIGNED';

alter table tracking_events alter column status type varchar(30);
alter table tracking_events
    alter column location_name type varchar(200),
    add column latitude double precision,
    add column longitude double precision;

alter table tracking_events
    add constraint chk_tracking_events_status check (status in (
        'PENDING_ACCEPTANCE', 'ACCEPTED', 'AT_PICKUP', 'PICKED_UP',
        'IN_TRANSIT', 'DELIVERED', 'FAILED', 'CANCELLED'));