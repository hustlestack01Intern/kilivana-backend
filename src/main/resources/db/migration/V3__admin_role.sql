alter table users drop constraint chk_users_role;
alter table users add constraint chk_users_role
    check (role in ('BUYER', 'SUPPLIER', 'FARMER', 'INSPECTOR', 'ADMIN'));