alter table refresh_tokens add column family_id uuid;
update refresh_tokens set family_id = gen_random_uuid() where family_id is null;
alter table refresh_tokens alter column family_id set not null;

create index idx_refresh_tokens_family_id on refresh_tokens (family_id);