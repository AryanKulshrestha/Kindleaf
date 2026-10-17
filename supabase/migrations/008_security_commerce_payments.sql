-- Kindleaf 008: security lockdown, server-side pricing, inventory, Leaf Points ledger,
-- coupon rules, verified reviews, returns, support tickets and Razorpay payment state.
--
-- Requires 001–003 (core tables, roles). Anything from 004–007 that is missing is created below,
-- so this works whether or not those were run. Safe to re-run: every statement is idempotent.

begin;

set search_path = public, extensions;

create extension if not exists pgcrypto;
create extension if not exists pg_trgm;

-- ════════════════════════════════════════════════════════════════════
-- 0. Prerequisites from migrations 004–007 (no-ops if already applied)
-- ════════════════════════════════════════════════════════════════════

-- 004: checkout charges
create table if not exists public.charges (
    id uuid primary key default gen_random_uuid(),
    key text not null unique,
    name text not null,
    amount integer not null default 0 check (amount >= 0),
    is_enabled boolean not null default true,
    free_above integer check (free_above is null or free_above >= 0),
    description text not null default '',
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

insert into public.charges (key, name, amount, is_enabled, free_above, description, sort_order)
values ('delivery', 'Delivery Fee', 40, true, 299,
        'Flat delivery fee. Waived when cart subtotal reaches the free-above amount.', 1)
on conflict (key) do nothing;

alter table public.charges enable row level security;

drop policy if exists "Charges are publicly readable" on public.charges;
create policy "Charges are publicly readable"
    on public.charges for select
    using (true);

drop policy if exists "Admins manage charges" on public.charges;
create policy "Admins manage charges"
    on public.charges for all
    using (public.is_admin())
    with check (public.is_admin());

-- 005: public images bucket
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('images', 'images', true, 5242880, array['image/jpeg', 'image/png', 'image/webp', 'image/gif'])
on conflict (id) do nothing;

drop policy if exists "Public read images" on storage.objects;
create policy "Public read images"
    on storage.objects for select
    using (bucket_id = 'images');

drop policy if exists "Admins delete images" on storage.objects;
create policy "Admins delete images"
    on storage.objects for delete
    to authenticated
    using (bucket_id = 'images' and public.is_admin());

-- 006: cancellation / refund columns
alter table public.orders
    add column if not exists cancelled_at timestamptz,
    add column if not exists cancel_refund_choice text not null default 'none',
    add column if not exists refund_status text not null default 'none';

alter table public.orders drop constraint if exists orders_cancel_refund_choice_check;
alter table public.orders
    add constraint orders_cancel_refund_choice_check
    check (cancel_refund_choice in ('none', 'leaf_points', 'money_refund'));

create or replace function public.is_cod_payment(method text)
returns boolean
language sql
immutable
as $$
    select lower(coalesce(method, '')) like '%cash on delivery%'
        or lower(coalesce(method, '')) ~ '(^|[^a-z])cod([^a-z]|$)';
$$;

-- 007: per-event notification toggles
create table if not exists public.notification_event_settings (
    event_key text primary key,
    label text not null,
    description text not null default '',
    is_enabled boolean not null default true,
    sort_order integer not null default 0,
    updated_at timestamptz not null default now()
);

insert into public.notification_event_settings (event_key, label, description, is_enabled, sort_order)
values
    ('order_placed', 'Order placed', 'Notify when a customer places an order', true, 1),
    ('order_cancelled', 'Order cancelled', 'Notify when an order is cancelled', true, 2),
    ('order_status_updated', 'Order status updated', 'Notify when admin advances order status (packed, shipped, etc.)', true, 3),
    ('leaf_points_added', 'Leaf points added', 'Notify when leaf points are credited to a customer', true, 4),
    ('leaf_points_deducted', 'Leaf points deducted', 'Notify when leaf points are removed from a customer', true, 5),
    ('refund_completed', 'Refund completed', 'Notify when a prepaid refund is marked completed', true, 6)
on conflict (event_key) do nothing;

alter table public.notification_event_settings enable row level security;

drop policy if exists "Anyone can read notification event settings" on public.notification_event_settings;
create policy "Anyone can read notification event settings"
    on public.notification_event_settings for select
    using (true);

drop policy if exists "Admins manage notification event settings" on public.notification_event_settings;
create policy "Admins manage notification event settings"
    on public.notification_event_settings for all
    using (public.is_admin())
    with check (public.is_admin());

create or replace function public.is_notification_event_enabled(p_key text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select coalesce(
        (select is_enabled from public.notification_event_settings where event_key = p_key),
        true
    );
$$;

grant execute on function public.is_notification_event_enabled(text) to anon, authenticated;

-- ════════════════════════════════════════════════════════════════════
-- 1. Store settings (single row)
-- ════════════════════════════════════════════════════════════════════

create table if not exists public.store_settings (
    id boolean primary key default true check (id),
    points_earn_percent integer not null default 5
        check (points_earn_percent between 0 and 100),
    points_redeem_max_percent integer not null default 20
        check (points_redeem_max_percent between 0 and 100),
    return_window_days integer not null default 7
        check (return_window_days between 0 and 90),
    unpaid_order_expiry_minutes integer not null default 30
        check (unpaid_order_expiry_minutes between 5 and 1440),
    max_quantity_per_item integer not null default 20
        check (max_quantity_per_item between 1 and 999),
    cod_enabled boolean not null default true,
    online_payments_enabled boolean not null default true,
    support_email text not null default '',
    support_phone text not null default '',
    support_whatsapp text not null default '',
    privacy_policy_url text not null default '',
    terms_url text not null default '',
    updated_at timestamptz not null default now()
);

insert into public.store_settings (id) values (true) on conflict (id) do nothing;

alter table public.store_settings enable row level security;

drop policy if exists "Store settings are readable" on public.store_settings;
create policy "Store settings are readable"
    on public.store_settings for select
    using (true);

drop policy if exists "Admins update store settings" on public.store_settings;
create policy "Admins update store settings"
    on public.store_settings for update
    using (public.is_admin())
    with check (public.is_admin());

-- ════════════════════════════════════════════════════════════════════
-- 2. Helpers
-- ════════════════════════════════════════════════════════════════════

-- Internal writes (ratings, vote counts) set this flag so protective triggers let them through.
create or replace function public._system_write_enabled()
returns boolean
language sql
stable
as $$
    select coalesce(current_setting('kindleaf.system', true), '') = 'on';
$$;

create or replace function public.order_status_label(p_status text)
returns text
language sql
immutable
as $$
    select case p_status
        when 'pending_payment' then 'Awaiting payment'
        when 'placed' then 'Order placed'
        when 'confirmed' then 'Confirmed'
        when 'packed' then 'Packed'
        when 'shipped' then 'Shipped'
        when 'out_for_delivery' then 'Out for delivery'
        when 'delivered' then 'Delivered'
        when 'cancelled' then 'Cancelled'
        when 'returned' then 'Returned'
        else initcap(replace(coalesce(p_status, ''), '_', ' '))
    end;
$$;

-- ════════════════════════════════════════════════════════════════════
-- 3. Notifications: link to orders, server-side creation only
-- ════════════════════════════════════════════════════════════════════

alter table public.notifications
    add column if not exists order_id uuid references public.orders (id) on delete set null;

insert into public.notification_event_settings (event_key, label, description, is_enabled, sort_order)
values
    ('payment_received', 'Payment received', 'Notify when an online payment succeeds', true, 7),
    ('payment_failed', 'Payment failed', 'Notify when an online payment fails', true, 8),
    ('return_updated', 'Return updates', 'Notify when a return is requested, approved or rejected', true, 9)
on conflict (event_key) do nothing;

create or replace function public._notify(
    p_user uuid,
    p_event text,
    p_title text,
    p_message text,
    p_type text default 'ORDER',
    p_order uuid default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if p_user is null or not public.is_notification_event_enabled(p_event) then
        return;
    end if;
    insert into public.notifications (user_id, title, message, type, order_id)
    values (p_user, p_title, p_message, p_type, p_order);
end;
$$;

-- Customers may no longer create notifications (they were able to forge them).
drop policy if exists "Users insert own notifications" on public.notifications;

-- Customers may only flip is_read, and may delete their own rows.
revoke update on public.notifications from anon, authenticated;
grant update (is_read) on public.notifications to authenticated;

drop policy if exists "Users delete own notifications" on public.notifications;
create policy "Users delete own notifications"
    on public.notifications for delete
    using (auth.uid() = user_id);

-- ════════════════════════════════════════════════════════════════════
-- 4. Profiles: block privilege escalation
-- ════════════════════════════════════════════════════════════════════

alter table public.profiles drop constraint if exists profiles_leaf_points_nonnegative;
alter table public.profiles
    add constraint profiles_leaf_points_nonnegative check (leaf_points >= 0) not valid;

create or replace function public.protect_profile_columns()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    -- Service role, SQL editor and auth hooks run without a user JWT.
    if auth.uid() is null then
        return new;
    end if;

    if tg_op = 'INSERT' then
        if not public.is_admin() then
            new.role := 'customer';
            new.leaf_points := 0;
        end if;
        return new;
    end if;

    if new.id is distinct from old.id then
        raise exception 'Profile id cannot change' using errcode = '42501';
    end if;
    if new.leaf_points is distinct from old.leaf_points
       and coalesce(current_setting('kindleaf.points_ledger', true), '') <> 'on' then
        raise exception 'Leaf Points can only change through the points ledger'
            using errcode = '42501';
    end if;
    if new.role is distinct from old.role and not public.is_admin() then
        raise exception 'Not allowed to change role' using errcode = '42501';
    end if;
    -- Email is owned by Supabase Auth; it is synced by the trigger below.
    if new.email is distinct from old.email then
        new.email := old.email;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_protect_profile_columns on public.profiles;
create trigger trg_protect_profile_columns
    before insert or update on public.profiles
    for each row
    execute function public.protect_profile_columns();

create or replace function public.handle_user_email_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.profiles
    set email = coalesce(new.email, ''),
        updated_at = now()
    where id = new.id;
    return new;
end;
$$;

drop trigger if exists on_auth_user_email_changed on auth.users;
create trigger on_auth_user_email_changed
    after update of email on auth.users
    for each row
    when (old.email is distinct from new.email)
    execute function public.handle_user_email_change();

-- ════════════════════════════════════════════════════════════════════
-- 5. Leaf Points ledger
-- ════════════════════════════════════════════════════════════════════

create table if not exists public.points_ledger (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    delta integer not null check (delta <> 0),
    balance_after integer not null check (balance_after >= 0),
    reason text not null check (reason in (
        'earned', 'redeemed', 'refund', 'cancel_return', 'return_reversal',
        'admin_adjustment', 'migration'
    )),
    note text not null default '',
    order_id uuid references public.orders (id) on delete set null,
    created_by uuid references auth.users (id) on delete set null,
    created_at timestamptz not null default now()
);

create index if not exists points_ledger_user_idx
    on public.points_ledger (user_id, created_at desc);

alter table public.points_ledger enable row level security;

drop policy if exists "Users view own points ledger" on public.points_ledger;
create policy "Users view own points ledger"
    on public.points_ledger for select
    using (auth.uid() = user_id or public.is_admin());

revoke insert, update, delete on public.points_ledger from anon, authenticated;

-- Opening balances for existing customers (runs once per user).
insert into public.points_ledger (user_id, delta, balance_after, reason, note)
select p.id, p.leaf_points, p.leaf_points, 'migration', 'Opening balance'
from public.profiles p
where p.leaf_points > 0
  and not exists (select 1 from public.points_ledger l where l.user_id = p.id);

create or replace function public._apply_points(
    p_user uuid,
    p_delta integer,
    p_reason text,
    p_note text default '',
    p_order uuid default null
)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    new_balance integer;
begin
    if p_user is null then
        raise exception 'Customer not found';
    end if;
    if coalesce(p_delta, 0) = 0 then
        select leaf_points into new_balance from public.profiles where id = p_user;
        return coalesce(new_balance, 0);
    end if;

    select leaf_points into new_balance from public.profiles where id = p_user for update;
    if new_balance is null then
        raise exception 'Customer profile not found';
    end if;
    if new_balance + p_delta < 0 then
        raise exception 'Insufficient Leaf Points (balance %)', new_balance;
    end if;

    perform set_config('kindleaf.points_ledger', 'on', true);
    update public.profiles
    set leaf_points = leaf_points + p_delta,
        updated_at = now()
    where id = p_user
    returning leaf_points into new_balance;
    perform set_config('kindleaf.points_ledger', 'off', true);

    if new_balance is null then
        raise exception 'Customer profile not found';
    end if;
    if new_balance < 0 then
        raise exception 'Insufficient Leaf Points';
    end if;

    insert into public.points_ledger (user_id, delta, balance_after, reason, note, order_id, created_by)
    values (p_user, p_delta, new_balance, p_reason, coalesce(p_note, ''), p_order, auth.uid());

    return new_balance;
end;
$$;

create or replace function public.points_ledger_after_insert()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    suffix text := case when new.note <> '' then ' (' || new.note || ')' else '' end;
begin
    if new.reason in ('redeemed', 'migration') then
        return null;
    end if;
    if new.delta > 0 then
        perform public._notify(
            new.user_id, 'leaf_points_added', 'Leaf Points added',
            new.delta || ' Leaf Points were added to your account' || suffix || '.',
            'GENERAL', new.order_id
        );
    else
        perform public._notify(
            new.user_id, 'leaf_points_deducted', 'Leaf Points deducted',
            abs(new.delta) || ' Leaf Points were deducted from your account' || suffix || '.',
            'GENERAL', new.order_id
        );
    end if;
    return null;
end;
$$;

drop trigger if exists trg_points_ledger_notify on public.points_ledger;
create trigger trg_points_ledger_notify
    after insert on public.points_ledger
    for each row
    execute function public.points_ledger_after_insert();

create or replace function public.admin_adjust_points(
    p_user_id uuid,
    p_delta integer,
    p_note text
)
returns integer
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.is_admin() then
        raise exception 'Admin only';
    end if;
    if coalesce(p_delta, 0) = 0 then
        raise exception 'Enter a non-zero amount';
    end if;
    if coalesce(trim(p_note), '') = '' then
        raise exception 'Add a reason for this adjustment';
    end if;
    return public._apply_points(p_user_id, p_delta, 'admin_adjustment', trim(p_note), null);
end;
$$;

-- ════════════════════════════════════════════════════════════════════
-- 6. Catalog: stock, MRP, category FK, protected ratings, search
-- ════════════════════════════════════════════════════════════════════

alter table public.items
    add column if not exists stock_qty integer not null default 100,
    add column if not exists low_stock_threshold integer not null default 5,
    add column if not exists mrp integer,
    add column if not exists category_id uuid references public.categories (id) on delete set null,
    add column if not exists updated_at timestamptz not null default now();

-- Existing products start with 100 units; new products start at 0 until stock is entered.
alter table public.items alter column stock_qty set default 0;

alter table public.items drop constraint if exists items_stock_nonnegative;
alter table public.items add constraint items_stock_nonnegative check (stock_qty >= 0);
alter table public.items drop constraint if exists items_low_stock_nonnegative;
alter table public.items add constraint items_low_stock_nonnegative check (low_stock_threshold >= 0);
alter table public.items drop constraint if exists items_price_nonnegative;
alter table public.items add constraint items_price_nonnegative check (price >= 0);
alter table public.items drop constraint if exists items_mrp_valid;
alter table public.items add constraint items_mrp_valid check (mrp is null or mrp >= 0);

update public.items i
set category_id = c.id
from public.categories c
where i.category_id is null
  and lower(c.name) = lower(i.category);

create index if not exists items_category_id_idx on public.items (category_id);
create index if not exists items_name_trgm_idx on public.items using gin (name gin_trgm_ops);

create or replace function public.items_before_write()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    new.updated_at := now();

    if new.category_id is not null then
        new.category := coalesce(
            (select c.name from public.categories c where c.id = new.category_id),
            new.category
        );
    elsif coalesce(new.category, '') <> '' then
        new.category_id := (
            select c.id from public.categories c
            where lower(c.name) = lower(new.category)
            limit 1
        );
    end if;

    -- Ratings are computed from real reviews only.
    if not public._system_write_enabled() then
        if tg_op = 'INSERT' then
            new.rating := 0;
            new.review_count := 0;
        else
            new.rating := old.rating;
            new.review_count := old.review_count;
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_items_before_write on public.items;
create trigger trg_items_before_write
    before insert or update on public.items
    for each row
    execute function public.items_before_write();

create or replace function public.categories_after_rename()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.items set category = new.name where category_id = new.id;
    return null;
end;
$$;

drop trigger if exists trg_categories_after_rename on public.categories;
create trigger trg_categories_after_rename
    after update of name on public.categories
    for each row
    when (old.name is distinct from new.name)
    execute function public.categories_after_rename();

create or replace function public.search_items(
    p_query text default '',
    p_category text default null,
    p_min_price integer default null,
    p_max_price integer default null,
    p_sort text default 'relevance',
    p_in_stock_only boolean default false,
    p_limit integer default 50,
    p_offset integer default 0
)
returns setof public.items
language sql
stable
security invoker
set search_path = public, extensions
as $$
    with q as (select coalesce(trim(p_query), '') as term)
    select i.*
    from public.items i, q
    where i.is_active
      and (
          q.term = ''
          or i.name ilike '%' || q.term || '%'
          or i.description ilike '%' || q.term || '%'
          or i.category ilike '%' || q.term || '%'
          or array_to_string(i.tags, ' ') ilike '%' || q.term || '%'
          or similarity(i.name, q.term) > 0.25
      )
      and (
          coalesce(p_category, '') in ('', 'All Teas')
          or lower(i.category) = lower(p_category)
          or exists (select 1 from unnest(i.tags) t where lower(t) = lower(p_category))
      )
      and (p_min_price is null or i.price >= p_min_price)
      and (p_max_price is null or i.price <= p_max_price)
      and (not coalesce(p_in_stock_only, false) or i.stock_qty > 0)
    order by
        case when p_sort = 'price_asc' then i.price end asc,
        case when p_sort = 'price_desc' then i.price end desc,
        case when p_sort = 'rating' then i.rating end desc,
        case when p_sort = 'popular' then i.review_count end desc,
        case when p_sort = 'newest' then i.created_at end desc,
        case when q.term <> '' then similarity(i.name, q.term) end desc nulls last,
        i.name asc
    limit least(greatest(coalesce(p_limit, 50), 1), 100)
    offset greatest(coalesce(p_offset, 0), 0);
$$;

-- ════════════════════════════════════════════════════════════════════
-- 7. Coupons: rules + redemptions
-- ════════════════════════════════════════════════════════════════════

alter table public.coupons
    add column if not exists min_order integer not null default 0,
    add column if not exists max_discount integer,
    add column if not exists fixed_amount integer not null default 0,
    add column if not exists starts_at timestamptz,
    add column if not exists usage_limit integer,
    add column if not exists per_user_limit integer;

alter table public.coupons drop constraint if exists coupons_rules_valid;
alter table public.coupons add constraint coupons_rules_valid check (
    min_order >= 0
    and fixed_amount >= 0
    and (max_discount is null or max_discount >= 0)
    and (usage_limit is null or usage_limit > 0)
    and (per_user_limit is null or per_user_limit > 0)
    and discount_percent between 0 and 100
) not valid;

update public.coupons set code = upper(trim(code)) where code <> upper(trim(code));
update public.coupons set min_order = 499 where code = 'KND15' and min_order = 0;

create or replace function public.coupons_normalize_code()
returns trigger
language plpgsql
as $$
begin
    new.code := upper(trim(new.code));
    return new;
end;
$$;

drop trigger if exists trg_coupons_normalize_code on public.coupons;
create trigger trg_coupons_normalize_code
    before insert or update of code on public.coupons
    for each row
    execute function public.coupons_normalize_code();

create table if not exists public.coupon_redemptions (
    id uuid primary key default gen_random_uuid(),
    coupon_id uuid not null references public.coupons (id) on delete cascade,
    code text not null,
    user_id uuid references auth.users (id) on delete set null,
    order_id uuid not null unique references public.orders (id) on delete cascade,
    discount integer not null default 0,
    created_at timestamptz not null default now()
);

create index if not exists coupon_redemptions_coupon_idx on public.coupon_redemptions (coupon_id);
create index if not exists coupon_redemptions_user_idx on public.coupon_redemptions (user_id);

alter table public.coupon_redemptions enable row level security;

drop policy if exists "Users view own coupon redemptions" on public.coupon_redemptions;
create policy "Users view own coupon redemptions"
    on public.coupon_redemptions for select
    using (auth.uid() = user_id or public.is_admin());

revoke insert, update, delete on public.coupon_redemptions from anon, authenticated;

-- ════════════════════════════════════════════════════════════════════
-- 8. Addresses: validation
-- A trigger (not a CHECK) so legacy rows saved by older app versions keep working: they are only
-- validated when their phone or pincode is edited, not when e.g. the default flag is cleared.
-- ════════════════════════════════════════════════════════════════════

alter table public.addresses drop constraint if exists addresses_pincode_format;
alter table public.addresses drop constraint if exists addresses_phone_format;

create or replace function public.addresses_validate()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'INSERT' or new.phone is distinct from old.phone then
        -- Accept +91 / 0 prefixes and separators, store 10 digits.
        new.phone := right(regexp_replace(coalesce(new.phone, ''), '\D', '', 'g'), 10);
        if new.phone !~ '^[6-9][0-9]{9}$' then
            raise exception 'Enter a valid 10-digit mobile number' using errcode = '23514';
        end if;
    end if;
    if tg_op = 'INSERT' or new.pincode is distinct from old.pincode then
        new.pincode := regexp_replace(coalesce(new.pincode, ''), '\s', '', 'g');
        if new.pincode !~ '^[1-9][0-9]{5}$' then
            raise exception 'Enter a valid 6-digit pincode' using errcode = '23514';
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_addresses_validate on public.addresses;
create trigger trg_addresses_validate
    before insert or update on public.addresses
    for each row
    execute function public.addresses_validate();

-- ════════════════════════════════════════════════════════════════════
-- 9. Orders: payment state, fulfilment fields, write lockdown
-- ════════════════════════════════════════════════════════════════════

alter table public.orders
    add column if not exists payment_status text not null default 'pending',
    add column if not exists razorpay_order_id text,
    add column if not exists razorpay_payment_id text,
    add column if not exists razorpay_refund_id text,
    add column if not exists paid_at timestamptz,
    add column if not exists expires_at timestamptz,
    add column if not exists extra_charges integer not null default 0,
    add column if not exists points_redeemed integer not null default 0,
    add column if not exists points_discount integer not null default 0,
    add column if not exists points_earned integer not null default 0,
    add column if not exists refund_amount integer not null default 0,
    add column if not exists tracking_number text,
    add column if not exists courier text,
    add column if not exists delivered_at timestamptz;

create unique index if not exists orders_razorpay_order_id_key
    on public.orders (razorpay_order_id)
    where razorpay_order_id is not null;
create index if not exists orders_created_at_idx on public.orders (created_at desc);
create index if not exists orders_pending_payment_idx
    on public.orders (expires_at)
    where status = 'pending_payment';

alter table public.orders drop constraint if exists orders_status_check;
alter table public.orders add constraint orders_status_check check (status in (
    'pending_payment', 'placed', 'confirmed', 'packed', 'shipped',
    'out_for_delivery', 'delivered', 'cancelled', 'returned'
));

alter table public.orders drop constraint if exists orders_payment_status_check;
alter table public.orders add constraint orders_payment_status_check check (
    payment_status in ('pending', 'paid', 'failed', 'cod_pending', 'refunded')
);

alter table public.orders drop constraint if exists orders_refund_status_check;
alter table public.orders add constraint orders_refund_status_check check (
    refund_status in ('none', 'pending', 'processing', 'completed', 'failed')
);

-- Backfill legacy orders. Old "UPI"/"Card" orders never collected money, so they stay 'pending'
-- and can never be refunded.
update public.orders
set payment_status = case
        when public.is_cod_payment(payment_method) and status = 'delivered' then 'paid'
        when public.is_cod_payment(payment_method) then 'cod_pending'
        else 'pending'
    end
where payment_status = 'pending'
  and razorpay_order_id is null
  and paid_at is null;

update public.orders
set delivered_at = updated_at
where status = 'delivered' and delivered_at is null;

-- Keep order history (anonymised) when a customer deletes their account.
alter table public.orders alter column user_id drop not null;
alter table public.orders drop constraint if exists orders_user_id_fkey;
alter table public.orders
    add constraint orders_user_id_fkey
    foreign key (user_id) references auth.users (id) on delete set null;

-- All order writes now go through RPCs (security definer) or the service role.
drop policy if exists "Users insert own orders" on public.orders;
drop policy if exists "Users insert own order items" on public.order_items;
revoke insert, update, delete on public.orders from anon, authenticated;
revoke insert, update, delete on public.order_items from anon, authenticated;

create table if not exists public.order_status_events (
    id uuid primary key default gen_random_uuid(),
    order_id uuid not null references public.orders (id) on delete cascade,
    status text not null,
    note text not null default '',
    created_by uuid references auth.users (id) on delete set null,
    created_at timestamptz not null default now()
);

create index if not exists order_status_events_order_idx
    on public.order_status_events (order_id, created_at);

alter table public.order_status_events enable row level security;

drop policy if exists "Users view own order events" on public.order_status_events;
create policy "Users view own order events"
    on public.order_status_events for select
    using (
        public.is_admin()
        or exists (
            select 1 from public.orders o
            where o.id = order_id and o.user_id = auth.uid()
        )
    );

revoke insert, update, delete on public.order_status_events from anon, authenticated;

-- Seed one event per existing order so timelines have a starting point.
insert into public.order_status_events (order_id, status, note, created_at)
select o.id, o.status, 'Imported', o.updated_at
from public.orders o
where not exists (select 1 from public.order_status_events e where e.order_id = o.id);

-- ── Status machine ──
create or replace function public.orders_before_update()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    allowed text[];
    earn_pct integer;
begin
    new.updated_at := now();

    if new.status is distinct from old.status then
        allowed := case old.status
            when 'pending_payment' then array['placed', 'cancelled']
            when 'placed' then array['confirmed', 'cancelled']
            when 'confirmed' then array['packed', 'cancelled']
            when 'packed' then array['shipped', 'cancelled']
            when 'shipped' then array['out_for_delivery', 'delivered']
            when 'out_for_delivery' then array['delivered']
            when 'delivered' then array['returned']
            else array[]::text[]
        end;
        if not (new.status = any (allowed)) then
            raise exception 'Order cannot move from % to %',
                public.order_status_label(old.status),
                public.order_status_label(new.status)
                using errcode = '22023';
        end if;

        if new.status = 'cancelled' then
            new.cancelled_at := coalesce(new.cancelled_at, now());
        elsif new.status = 'placed' then
            new.expires_at := null;
        elsif new.status = 'delivered' then
            new.delivered_at := coalesce(new.delivered_at, now());
            if new.payment_status = 'cod_pending' then
                new.payment_status := 'paid';
                new.paid_at := coalesce(new.paid_at, now());
            end if;
            select points_earn_percent into earn_pct from public.store_settings where id;
            -- 1 Leaf Point = ₹0.10, so earn% of the total in rupees × 10 points.
            new.points_earned := greatest(new.total, 0) * coalesce(earn_pct, 0) / 10;
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_orders_before_update on public.orders;
create trigger trg_orders_before_update
    before update on public.orders
    for each row
    execute function public.orders_before_update();

create or replace function public._restock_order(p_order_id uuid)
returns void
language sql
security definer
set search_path = public
as $$
    update public.items i
    set stock_qty = i.stock_qty + oi.qty
    from (
        select item_id, sum(quantity)::integer as qty
        from public.order_items
        where order_id = p_order_id and item_id is not null
        group by item_id
    ) oi
    where i.id = oi.item_id;
$$;

create or replace function public.orders_after_insert()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.order_status_events (order_id, status, created_by)
    values (new.id, new.status, auth.uid());

    if new.status = 'placed' then
        perform public._notify(
            new.user_id, 'order_placed', 'Order placed',
            'Thank you! Your order #' || upper(left(new.id::text, 8)) || ' has been placed.',
            'ORDER', new.id
        );
    end if;
    return null;
end;
$$;

drop trigger if exists trg_orders_after_insert on public.orders;
create trigger trg_orders_after_insert
    after insert on public.orders
    for each row
    execute function public.orders_after_insert();

create or replace function public.orders_after_update()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    short text := upper(left(new.id::text, 8));
    by_admin boolean := coalesce(current_setting('kindleaf.cancelled_by', true), '') = 'admin';
    by_system boolean := coalesce(current_setting('kindleaf.cancelled_by', true), '') = 'system';
    reversal integer;
    msg text;
begin
    if new.status is distinct from old.status then
        insert into public.order_status_events (order_id, status, note, created_by)
        values (
            new.id,
            new.status,
            coalesce(current_setting('kindleaf.status_note', true), ''),
            auth.uid()
        );

        if new.status = 'placed' and new.payment_status = 'paid' then
            perform public._notify(
                new.user_id, 'payment_received', 'Payment received',
                'Payment received. Your order #' || short || ' has been placed.',
                'ORDER', new.id
            );
        elsif new.status = 'placed' then
            perform public._notify(
                new.user_id, 'order_placed', 'Order placed',
                'Thank you! Your order #' || short || ' has been placed. Pay on delivery.',
                'ORDER', new.id
            );

        elsif new.status = 'cancelled' then
            perform public._restock_order(new.id);
            delete from public.coupon_redemptions where order_id = new.id;
            if new.points_redeemed > 0 and new.user_id is not null then
                perform public._apply_points(
                    new.user_id, new.points_redeemed, 'cancel_return',
                    'Returned from cancelled order #' || short, new.id
                );
            end if;

            msg := case
                when by_system then
                    'Order #' || short || ' was cancelled because payment was not completed in time.'
                when new.cancel_refund_choice = 'leaf_points' then
                    'Your order #' || short || ' was cancelled. Leaf Points were credited instantly.'
                when new.cancel_refund_choice = 'money_refund' then
                    'Your order #' || short || ' was cancelled. Your refund will reach your original payment method in 5–7 working days.'
                when by_admin then
                    'Your order #' || short || ' was cancelled by Kindleaf.'
                else
                    'Your order #' || short || ' was cancelled.'
            end;
            perform public._notify(new.user_id, 'order_cancelled', 'Order cancelled', msg, 'ORDER', new.id);

        elsif new.status = 'delivered' then
            if new.points_earned > 0 and new.user_id is not null then
                perform public._apply_points(
                    new.user_id, new.points_earned, 'earned',
                    'Earned on order #' || short, new.id
                );
            end if;
            perform public._notify(
                new.user_id, 'order_status_updated', 'Order delivered',
                'Your order #' || short || ' has been delivered. Enjoy your tea!',
                'ORDER', new.id
            );

        elsif new.status = 'returned' then
            perform public._restock_order(new.id);
            if new.points_earned > 0 and new.user_id is not null then
                select least(new.points_earned, leaf_points) into reversal
                from public.profiles where id = new.user_id;
                if coalesce(reversal, 0) > 0 then
                    perform public._apply_points(
                        new.user_id, -reversal, 'return_reversal',
                        'Points earned on returned order #' || short, new.id
                    );
                end if;
            end if;

        else
            msg := 'Your order #' || short || ' is now ' ||
                lower(public.order_status_label(new.status)) || '.';
            if new.status = 'shipped' and coalesce(new.tracking_number, '') <> '' then
                msg := msg || ' Tracking: ' || coalesce(new.courier || ' ', '') || new.tracking_number;
            end if;
            perform public._notify(
                new.user_id, 'order_status_updated',
                public.order_status_label(new.status), msg, 'ORDER', new.id
            );
        end if;
    end if;

    if new.refund_status is distinct from old.refund_status
       and new.refund_status = 'completed'
       and new.cancel_refund_choice = 'money_refund' then
        perform public._notify(
            new.user_id, 'refund_completed', 'Refund completed',
            'Your refund of ₹' || new.refund_amount || ' for order #' || short ||
            ' has been processed to your original payment method.',
            'ORDER', new.id
        );
    end if;

    if new.payment_status is distinct from old.payment_status
       and new.payment_status = 'failed' then
        perform public._notify(
            new.user_id, 'payment_failed', 'Payment failed',
            'Payment for order #' || short || ' did not go through. You can retry from My Orders.',
            'ORDER', new.id
        );
    end if;

    return null;
end;
$$;

drop trigger if exists trg_orders_after_update on public.orders;
create trigger trg_orders_after_update
    after update on public.orders
    for each row
    execute function public.orders_after_update();

-- ════════════════════════════════════════════════════════════════════
-- 10. Cart + wishlist (synced across devices)
-- ════════════════════════════════════════════════════════════════════

create table if not exists public.cart_items (
    user_id uuid not null references auth.users (id) on delete cascade,
    item_id uuid not null references public.items (id) on delete cascade,
    quantity integer not null default 1 check (quantity between 1 and 999),
    updated_at timestamptz not null default now(),
    primary key (user_id, item_id)
);

create table if not exists public.wishlist_items (
    user_id uuid not null references auth.users (id) on delete cascade,
    item_id uuid not null references public.items (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, item_id)
);

alter table public.cart_items enable row level security;
alter table public.wishlist_items enable row level security;

drop policy if exists "Users manage own cart" on public.cart_items;
create policy "Users manage own cart"
    on public.cart_items for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "Users manage own wishlist" on public.wishlist_items;
create policy "Users manage own wishlist"
    on public.wishlist_items for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

-- ════════════════════════════════════════════════════════════════════
-- 11. Pricing (single source of truth) + order placement
-- ════════════════════════════════════════════════════════════════════

-- p_items: [{"item_id": "<uuid>", "quantity": 2}, ...]
create or replace function public._price_cart(
    p_user uuid,
    p_items jsonb,
    p_coupon_code text,
    p_redeem_points integer
)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    s public.store_settings;
    req record;
    it public.items;
    ch record;
    c public.coupons;
    lines jsonb := '[]'::jsonb;
    errors jsonb := '[]'::jsonb;
    charge_lines jsonb := '[]'::jsonb;
    subtotal integer := 0;
    delivery integer := 0;
    extras integer := 0;
    amt integer;
    coupon_code text;
    coupon_discount integer := 0;
    coupon_error text;
    balance integer := 0;
    max_points_value integer := 0;
    points_discount integer := 0;
    total integer;
begin
    select * into s from public.store_settings where id;

    if p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
        errors := errors || to_jsonb('Your cart is empty'::text);
    end if;

    for req in
        select (e ->> 'item_id')::uuid as item_id,
               sum(greatest(coalesce((e ->> 'quantity')::integer, 0), 0))::integer as quantity
        from jsonb_array_elements(
            case when jsonb_typeof(p_items) = 'array' then p_items else '[]'::jsonb end
        ) e
        group by 1
    loop
        select * into it from public.items where id = req.item_id;
        if not found or not it.is_active then
            errors := errors || to_jsonb('A product in your cart is no longer available'::text);
            continue;
        end if;
        if req.quantity < 1 then
            continue;
        end if;
        if req.quantity > s.max_quantity_per_item then
            errors := errors || to_jsonb(format('You can order at most %s of %s', s.max_quantity_per_item, it.name));
        end if;
        if it.stock_qty < req.quantity then
            errors := errors || to_jsonb(
                case when it.stock_qty = 0
                    then format('%s is out of stock', it.name)
                    else format('Only %s left of %s', it.stock_qty, it.name)
                end
            );
        end if;
        subtotal := subtotal + it.price * req.quantity;
        lines := lines || jsonb_build_object(
            'item_id', it.id,
            'name', it.name,
            'price', it.price,
            'quantity', req.quantity,
            'image_url', it.image_url,
            'size', it.size,
            'line_total', it.price * req.quantity,
            'stock_qty', it.stock_qty
        );
    end loop;

    for ch in select * from public.charges where is_enabled order by sort_order loop
        amt := case
            when ch.free_above is not null and subtotal >= ch.free_above then 0
            else greatest(ch.amount, 0)
        end;
        if ch.key = 'delivery' then
            delivery := delivery + amt;
        else
            extras := extras + amt;
        end if;
        charge_lines := charge_lines || jsonb_build_object(
            'key', ch.key, 'name', ch.name, 'amount', amt, 'free_above', ch.free_above
        );
    end loop;

    if coalesce(trim(p_coupon_code), '') <> '' then
        select * into c from public.coupons where code = upper(trim(p_coupon_code)) limit 1;
        if not found or not c.is_active then
            coupon_error := 'Invalid coupon code';
        elsif c.starts_at is not null and c.starts_at > now() then
            coupon_error := 'This coupon is not active yet';
        elsif c.valid_until is not null and c.valid_until < current_date then
            coupon_error := 'This coupon has expired';
        elsif subtotal < c.min_order then
            coupon_error := format('Add items worth ₹%s more to use this coupon', c.min_order - subtotal);
        elsif c.usage_limit is not null
              and (select count(*) from public.coupon_redemptions r where r.coupon_id = c.id) >= c.usage_limit then
            coupon_error := 'This coupon has reached its usage limit';
        elsif c.per_user_limit is not null and p_user is not null
              and (select count(*) from public.coupon_redemptions r
                   where r.coupon_id = c.id and r.user_id = p_user) >= c.per_user_limit then
            coupon_error := 'You have already used this coupon';
        elsif c.type = 'SHIPPING' and delivery = 0 then
            coupon_error := 'Delivery is already free on this order';
        else
            coupon_discount := case
                when c.type = 'SHIPPING' then delivery
                when c.fixed_amount > 0 then c.fixed_amount
                else subtotal * least(greatest(c.discount_percent, 0), 100) / 100
            end;
            if c.max_discount is not null and c.max_discount > 0 then
                coupon_discount := least(coupon_discount, c.max_discount);
            end if;
            coupon_discount := least(coupon_discount, subtotal + delivery);
            coupon_code := c.code;
        end if;
    end if;

    if p_user is not null then
        select leaf_points into balance from public.profiles where id = p_user;
    end if;
    balance := coalesce(balance, 0);

    max_points_value := greatest((subtotal - coupon_discount) * s.points_redeem_max_percent / 100, 0);
    points_discount := greatest(
        least(coalesce(p_redeem_points, 0) / 10, balance / 10, max_points_value),
        0
    );

    total := greatest(subtotal - coupon_discount + delivery + extras - points_discount, 0);

    return jsonb_build_object(
        'lines', lines,
        'errors', errors,
        'subtotal', subtotal,
        'delivery_fee', delivery,
        'extra_charges', extras,
        'charges', charge_lines,
        'coupon_code', coupon_code,
        'coupon_discount', coupon_discount,
        'coupon_error', coupon_error,
        'points_balance', balance,
        'points_max', max_points_value * 10,
        'points_applied', points_discount * 10,
        'points_discount', points_discount,
        'total', total,
        'cod_enabled', s.cod_enabled,
        'online_enabled', s.online_payments_enabled
    );
end;
$$;

create or replace function public.quote_cart(
    p_items jsonb,
    p_coupon_code text default null,
    p_redeem_points integer default 0
)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
    select public._price_cart(auth.uid(), p_items, p_coupon_code, p_redeem_points);
$$;

create or replace function public.expire_unpaid_orders()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    n integer := 0;
begin
    for r in
        select id from public.orders
        where status = 'pending_payment'
          and payment_status <> 'paid'
          and expires_at is not null
          and expires_at < now()
        for update skip locked
    loop
        perform set_config('kindleaf.cancelled_by', 'system', true);
        update public.orders
        set status = 'cancelled',
            cancel_refund_choice = 'none',
            refund_status = 'none'
        where id = r.id;
        n := n + 1;
    end loop;
    perform set_config('kindleaf.cancelled_by', '', true);
    return n;
end;
$$;

-- p_payment_method: 'cod' or 'online'
create or replace function public.place_order(
    p_address_id uuid,
    p_items jsonb,
    p_payment_method text,
    p_coupon_code text default null,
    p_redeem_points integer default 0
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    uid uuid := auth.uid();
    s public.store_settings;
    a public.addresses;
    q jsonb;
    method text := lower(coalesce(trim(p_payment_method), ''));
    o public.orders;
    q_total integer;
begin
    if uid is null then
        raise exception 'Please sign in to place an order';
    end if;

    perform public.expire_unpaid_orders();

    select * into s from public.store_settings where id;
    if method not in ('cod', 'online') then
        raise exception 'Choose a payment method';
    end if;
    if method = 'cod' and not s.cod_enabled then
        raise exception 'Cash on Delivery is currently unavailable';
    end if;
    if method = 'online' and not s.online_payments_enabled then
        raise exception 'Online payment is currently unavailable';
    end if;

    select * into a from public.addresses where id = p_address_id and user_id = uid;
    if not found then
        raise exception 'Please choose a delivery address';
    end if;

    -- Lock products (stable order avoids deadlocks) and the profile (points balance).
    perform 1 from public.items
    where id in (
        select (e ->> 'item_id')::uuid
        from jsonb_array_elements(
            case when jsonb_typeof(p_items) = 'array' then p_items else '[]'::jsonb end
        ) e
    )
    order by id
    for update;
    perform 1 from public.profiles where id = uid for update;

    q := public._price_cart(uid, p_items, p_coupon_code, p_redeem_points);

    if jsonb_array_length(q -> 'errors') > 0 then
        raise exception '%', q -> 'errors' ->> 0;
    end if;
    if coalesce(trim(p_coupon_code), '') <> '' and q ->> 'coupon_error' is not null then
        raise exception '%', q ->> 'coupon_error';
    end if;
    if jsonb_array_length(q -> 'lines') = 0 then
        raise exception 'Your cart is empty';
    end if;

    q_total := (q ->> 'total')::integer;

    insert into public.orders (
        user_id, status, payment_status, paid_at,
        subtotal, delivery_fee, extra_charges, discount,
        points_redeemed, points_discount, total, payment_method,
        delivery_name, delivery_address_line, delivery_city,
        delivery_state, delivery_pincode, delivery_phone,
        coupon_code, estimated_delivery, expires_at
    ) values (
        uid,
        case when method = 'cod' or q_total = 0 then 'placed' else 'pending_payment' end,
        case when q_total = 0 then 'paid' when method = 'cod' then 'cod_pending' else 'pending' end,
        case when q_total = 0 then now() end,
        (q ->> 'subtotal')::integer,
        (q ->> 'delivery_fee')::integer,
        (q ->> 'extra_charges')::integer,
        (q ->> 'coupon_discount')::integer,
        (q ->> 'points_applied')::integer,
        (q ->> 'points_discount')::integer,
        q_total,
        case
            when q_total = 0 then 'Leaf Points / Coupon'
            when method = 'cod' then 'Cash on Delivery'
            else 'Online Payment'
        end,
        a.name, a.address_line, a.city, a.state, a.pincode, a.phone,
        q ->> 'coupon_code',
        to_char(current_date + 5, 'DD Mon YYYY'),
        case when method = 'online' and q_total > 0
            then now() + make_interval(mins => s.unpaid_order_expiry_minutes)
        end
    )
    returning * into o;

    insert into public.order_items (order_id, item_id, name, price, quantity, image_url, size)
    select o.id,
           (l ->> 'item_id')::uuid,
           l ->> 'name',
           (l ->> 'price')::integer,
           (l ->> 'quantity')::integer,
           coalesce(l ->> 'image_url', ''),
           coalesce(l ->> 'size', '')
    from jsonb_array_elements(q -> 'lines') l;

    update public.items i
    set stock_qty = i.stock_qty - (l ->> 'quantity')::integer
    from jsonb_array_elements(q -> 'lines') l
    where i.id = (l ->> 'item_id')::uuid;

    if q ->> 'coupon_code' is not null then
        insert into public.coupon_redemptions (coupon_id, code, user_id, order_id, discount)
        select c.id, c.code, uid, o.id, (q ->> 'coupon_discount')::integer
        from public.coupons c
        where c.code = q ->> 'coupon_code';
    end if;

    if (q ->> 'points_applied')::integer > 0 then
        perform public._apply_points(
            uid, -((q ->> 'points_applied')::integer), 'redeemed',
            'Redeemed on order #' || upper(left(o.id::text, 8)), o.id
        );
    end if;

    delete from public.cart_items where user_id = uid;

    return o;
end;
$$;

-- Customer fallback when online payment fails or is unavailable.
create or replace function public.switch_order_to_cod(p_order_id uuid)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
    s public.store_settings;
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;
    select * into s from public.store_settings where id;
    if not s.cod_enabled then
        raise exception 'Cash on Delivery is currently unavailable';
    end if;

    select * into o from public.orders where id = p_order_id for update;
    if not found or o.user_id is distinct from auth.uid() then
        raise exception 'Order not found';
    end if;
    if o.status <> 'pending_payment' or o.payment_status not in ('pending', 'failed') then
        raise exception 'This order is no longer awaiting payment';
    end if;

    update public.orders
    set status = 'placed',
        payment_status = 'cod_pending',
        payment_method = 'Cash on Delivery',
        expires_at = null
    where id = o.id
    returning * into o;
    return o;
end;
$$;

-- ════════════════════════════════════════════════════════════════════
-- 12. Cancellation + refunds
-- ════════════════════════════════════════════════════════════════════

create or replace function public._cancel_order(
    p_order_id uuid,
    p_refund_choice text,
    p_by_admin boolean
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
    choice text := coalesce(nullif(trim(p_refund_choice), ''), 'none');
begin
    select * into o from public.orders where id = p_order_id for update;
    if not found then
        raise exception 'Order not found';
    end if;

    if p_by_admin then
        if o.status not in ('pending_payment', 'placed', 'confirmed', 'packed') then
            raise exception 'Order cannot be cancelled once it has shipped';
        end if;
    else
        if o.user_id is distinct from auth.uid() then
            raise exception 'Not your order';
        end if;
        if o.status not in ('pending_payment', 'placed', 'confirmed') then
            raise exception 'Order can no longer be cancelled';
        end if;
    end if;

    -- Refunds only exist for money that was actually collected.
    if o.payment_status = 'paid' and o.total > 0 then
        if choice not in ('leaf_points', 'money_refund') then
            if p_by_admin then
                choice := 'money_refund';
            else
                raise exception 'Choose leaf_points or money_refund for prepaid orders';
            end if;
        end if;
    else
        choice := 'none';
    end if;

    perform set_config('kindleaf.cancelled_by', case when p_by_admin then 'admin' else 'customer' end, true);

    update public.orders
    set status = 'cancelled',
        cancel_refund_choice = choice,
        refund_status = case choice
            when 'leaf_points' then 'completed'
            when 'money_refund' then 'pending'
            else 'none'
        end,
        refund_amount = case when choice = 'none' then 0 else o.total end,
        payment_status = case when choice = 'leaf_points' then 'refunded' else payment_status end
    where id = o.id
    returning * into o;

    perform set_config('kindleaf.cancelled_by', '', true);

    if choice = 'leaf_points' then
        perform public._apply_points(
            o.user_id, o.total * 10, 'refund',
            'Refund for cancelled order #' || upper(left(o.id::text, 8)), o.id
        );
    end if;

    return o;
end;
$$;

create or replace function public.cancel_own_order(
    p_order_id uuid,
    p_refund_choice text default 'none'
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;
    return public._cancel_order(p_order_id, p_refund_choice, false);
end;
$$;

create or replace function public.admin_cancel_order(
    p_order_id uuid,
    p_refund_choice text default null
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.is_admin() then
        raise exception 'Admin only';
    end if;
    return public._cancel_order(p_order_id, p_refund_choice, true);
end;
$$;

-- Manual completion (bank transfer for COD returns, or when Razorpay refunds were done by hand).
drop function if exists public.admin_complete_refund(uuid);
create or replace function public.admin_complete_refund(
    p_order_id uuid,
    p_reference text default null
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
begin
    if not public.is_admin() then
        raise exception 'Admin only';
    end if;

    select * into o from public.orders where id = p_order_id for update;
    if not found then
        raise exception 'Order not found';
    end if;
    if o.refund_status not in ('pending', 'processing', 'failed') then
        raise exception 'No pending refund for this order';
    end if;

    update public.orders
    set refund_status = 'completed',
        payment_status = 'refunded',
        razorpay_refund_id = coalesce(nullif(trim(p_reference), ''), razorpay_refund_id)
    where id = o.id
    returning * into o;

    return o;
end;
$$;

create or replace function public.admin_update_order_status(
    p_order_id uuid,
    p_status text default null,
    p_tracking_number text default null,
    p_courier text default null,
    p_note text default null
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
begin
    if not public.is_admin() then
        raise exception 'Admin only';
    end if;
    if p_status = 'cancelled' then
        raise exception 'Use cancel to cancel an order';
    end if;
    if p_status = 'returned' then
        raise exception 'Returns are completed from the Returns screen';
    end if;
    if p_status = 'placed' then
        raise exception 'Orders move to placed automatically when payment is received';
    end if;

    perform set_config('kindleaf.status_note', coalesce(p_note, ''), true);

    update public.orders
    set status = coalesce(nullif(trim(p_status), ''), status),
        tracking_number = coalesce(nullif(trim(p_tracking_number), ''), tracking_number),
        courier = coalesce(nullif(trim(p_courier), ''), courier)
    where id = p_order_id
    returning * into o;

    perform set_config('kindleaf.status_note', '', true);

    -- Checked via o.id: PERFORM above resets FOUND.
    if o.id is null then
        raise exception 'Order not found';
    end if;
    return o;
end;
$$;

-- ════════════════════════════════════════════════════════════════════
-- 13. Razorpay payment state (service role only — called by Edge Functions)
-- ════════════════════════════════════════════════════════════════════

create or replace function public.payment_attach_razorpay_order(
    p_order_id uuid,
    p_razorpay_order_id text
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
begin
    update public.orders
    set razorpay_order_id = p_razorpay_order_id
    where id = p_order_id
      and status = 'pending_payment'
      and razorpay_order_id is null
    returning * into o;

    if not found then
        select * into o from public.orders where id = p_order_id;
    end if;
    return o;
end;
$$;

create or replace function public.payment_mark_paid(
    p_razorpay_order_id text,
    p_razorpay_payment_id text,
    p_method text default null,
    p_amount_paise bigint default null
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
begin
    select * into o from public.orders where razorpay_order_id = p_razorpay_order_id for update;
    if not found then
        raise exception 'No order for Razorpay order %', p_razorpay_order_id;
    end if;
    if p_amount_paise is not null and p_amount_paise <> o.total::bigint * 100 then
        raise exception 'Amount mismatch for order %', o.id;
    end if;

    -- Idempotent: webhook and client verification may both arrive.
    if o.payment_status in ('paid', 'refunded') then
        return o;
    end if;

    if o.status = 'cancelled' then
        -- Paid after the order expired or was cancelled: refund it automatically.
        update public.orders
        set payment_status = 'paid',
            paid_at = now(),
            razorpay_payment_id = p_razorpay_payment_id,
            cancel_refund_choice = 'money_refund',
            refund_status = 'pending',
            refund_amount = o.total
        where id = o.id
        returning * into o;

        perform public._notify(
            o.user_id, 'order_cancelled', 'Payment will be refunded',
            'We received your payment for order #' || upper(left(o.id::text, 8)) ||
            ' after it was cancelled. It will be refunded to your original payment method.',
            'ORDER', o.id
        );
        return o;
    end if;

    update public.orders
    set payment_status = 'paid',
        paid_at = now(),
        razorpay_payment_id = p_razorpay_payment_id,
        payment_method = case
            when coalesce(p_method, '') <> '' then 'Online · ' || upper(p_method)
            else payment_method
        end,
        status = case when status = 'pending_payment' then 'placed' else status end
    where id = o.id
    returning * into o;

    return o;
end;
$$;

create or replace function public.payment_mark_failed(
    p_razorpay_order_id text,
    p_reason text default null
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
begin
    update public.orders
    set payment_status = 'failed'
    where razorpay_order_id = p_razorpay_order_id
      and payment_status = 'pending'
    returning * into o;

    if not found then
        select * into o from public.orders where razorpay_order_id = p_razorpay_order_id;
    end if;
    return o;
end;
$$;

-- Allows a retry after a failed attempt (same Razorpay order is reused).
create or replace function public.payment_reset_for_retry(p_order_id uuid)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
begin
    update public.orders
    set payment_status = 'pending'
    where id = p_order_id
      and status = 'pending_payment'
      and payment_status = 'failed'
    returning * into o;

    if not found then
        select * into o from public.orders where id = p_order_id;
    end if;
    return o;
end;
$$;

create or replace function public.payment_set_refund(
    p_order_id uuid,
    p_refund_id text,
    p_status text
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
begin
    if p_status not in ('processing', 'completed', 'failed') then
        raise exception 'Invalid refund status %', p_status;
    end if;

    update public.orders
    set refund_status = p_status,
        razorpay_refund_id = coalesce(p_refund_id, razorpay_refund_id),
        payment_status = case when p_status = 'completed' then 'refunded' else payment_status end
    where id = p_order_id
      and refund_status in ('pending', 'processing', 'failed')
    returning * into o;

    if not found then
        select * into o from public.orders where id = p_order_id;
    end if;
    return o;
end;
$$;

-- ════════════════════════════════════════════════════════════════════
-- 14. Reviews: verified purchases only, real aggregates, helpful votes
-- ════════════════════════════════════════════════════════════════════

do $$
begin
    create unique index if not exists reviews_item_user_key
        on public.reviews (item_id, user_id)
        where user_id is not null;
exception when unique_violation then
    raise notice 'Duplicate reviews exist; unique index reviews_item_user_key was not created.';
end $$;

alter table public.reviews drop constraint if exists reviews_text_length;
alter table public.reviews
    add constraint reviews_text_length check (char_length(text) <= 2000) not valid;

create or replace function public.reviews_before_write()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    p public.profiles;
begin
    if auth.uid() is null or public._system_write_enabled() or public.is_admin() then
        return new;
    end if;

    if tg_op = 'INSERT' then
        if not exists (
            select 1
            from public.orders o
            join public.order_items oi on oi.order_id = o.id
            where o.user_id = auth.uid()
              and oi.item_id = new.item_id
              and o.status = 'delivered'
        ) then
            raise exception 'You can review a product after it has been delivered to you';
        end if;

        select * into p from public.profiles where id = auth.uid();
        new.user_id := auth.uid();
        new.author := coalesce(nullif(trim(p.name), ''), 'Customer');
        new.author_initial := upper(left(new.author, 1));
        new.is_verified := true;
        new.is_visible := true;
        new.helpful_count := 0;
        new.unhelpful_count := 0;
        new.created_at := now();
    else
        if old.user_id is distinct from auth.uid() then
            raise exception 'Not your review';
        end if;
        new.user_id := old.user_id;
        new.item_id := old.item_id;
        new.author := old.author;
        new.author_initial := old.author_initial;
        new.is_verified := old.is_verified;
        new.is_visible := old.is_visible;
        new.helpful_count := old.helpful_count;
        new.unhelpful_count := old.unhelpful_count;
        new.created_at := old.created_at;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_reviews_before_write on public.reviews;
create trigger trg_reviews_before_write
    before insert or update on public.reviews
    for each row
    execute function public.reviews_before_write();

drop policy if exists "Users update own reviews" on public.reviews;
create policy "Users update own reviews"
    on public.reviews for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "Users delete own reviews" on public.reviews;
create policy "Users delete own reviews"
    on public.reviews for delete
    using (auth.uid() = user_id);

create or replace function public._recompute_item_rating(p_item uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    perform set_config('kindleaf.system', 'on', true);
    update public.items i
    set rating = coalesce((
            select round(avg(r.rating)::numeric, 1)::real
            from public.reviews r
            where r.item_id = i.id and r.is_visible
        ), 0),
        review_count = (
            select count(*)
            from public.reviews r
            where r.item_id = i.id and r.is_visible
        )
    where i.id = p_item;
    perform set_config('kindleaf.system', 'off', true);
end;
$$;

create or replace function public.reviews_after_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if tg_op in ('UPDATE', 'DELETE') then
        perform public._recompute_item_rating(old.item_id);
    end if;
    if tg_op in ('INSERT', 'UPDATE') and (tg_op = 'INSERT' or new.item_id is distinct from old.item_id) then
        perform public._recompute_item_rating(new.item_id);
    end if;
    return null;
end;
$$;

drop trigger if exists trg_reviews_after_change on public.reviews;
create trigger trg_reviews_after_change
    after insert or update or delete on public.reviews
    for each row
    execute function public.reviews_after_change();

-- Replace hand-typed ratings with real aggregates.
do $$
declare
    r record;
begin
    for r in select id from public.items loop
        perform public._recompute_item_rating(r.id);
    end loop;
end $$;

create table if not exists public.review_votes (
    review_id uuid not null references public.reviews (id) on delete cascade,
    user_id uuid not null references auth.users (id) on delete cascade,
    is_helpful boolean not null,
    created_at timestamptz not null default now(),
    primary key (review_id, user_id)
);

alter table public.review_votes enable row level security;

drop policy if exists "Users manage own review votes" on public.review_votes;
create policy "Users manage own review votes"
    on public.review_votes for all
    using (auth.uid() = user_id)
    with check (
        auth.uid() = user_id
        and not exists (
            select 1 from public.reviews r
            where r.id = review_id and r.user_id = auth.uid()
        )
    );

create or replace function public.review_votes_after_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    rid uuid := coalesce(new.review_id, old.review_id);
begin
    perform set_config('kindleaf.system', 'on', true);
    update public.reviews
    set helpful_count = (select count(*) from public.review_votes v where v.review_id = rid and v.is_helpful),
        unhelpful_count = (select count(*) from public.review_votes v where v.review_id = rid and not v.is_helpful)
    where id = rid;
    perform set_config('kindleaf.system', 'off', true);
    return null;
end;
$$;

drop trigger if exists trg_review_votes_after_change on public.review_votes;
create trigger trg_review_votes_after_change
    after insert or update or delete on public.review_votes
    for each row
    execute function public.review_votes_after_change();

-- ════════════════════════════════════════════════════════════════════
-- 15. Returns
-- ════════════════════════════════════════════════════════════════════

create table if not exists public.return_requests (
    id uuid primary key default gen_random_uuid(),
    order_id uuid not null references public.orders (id) on delete cascade,
    user_id uuid references auth.users (id) on delete set null,
    reason text not null,
    details text not null default '',
    refund_choice text not null default 'money_refund'
        check (refund_choice in ('none', 'leaf_points', 'money_refund')),
    status text not null default 'requested'
        check (status in ('requested', 'approved', 'rejected')),
    admin_note text not null default '',
    created_at timestamptz not null default now(),
    resolved_at timestamptz
);

create unique index if not exists return_requests_open_order_key
    on public.return_requests (order_id)
    where status in ('requested', 'approved');

alter table public.return_requests enable row level security;

drop policy if exists "Users view own returns" on public.return_requests;
create policy "Users view own returns"
    on public.return_requests for select
    using (auth.uid() = user_id or public.is_admin());

revoke insert, update, delete on public.return_requests from anon, authenticated;

create or replace function public.request_return(
    p_order_id uuid,
    p_reason text,
    p_details text default '',
    p_refund_choice text default 'money_refund'
)
returns public.return_requests
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
    s public.store_settings;
    r public.return_requests;
    choice text := coalesce(nullif(trim(p_refund_choice), ''), 'money_refund');
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;
    select * into s from public.store_settings where id;

    select * into o from public.orders where id = p_order_id for update;
    if not found or o.user_id is distinct from auth.uid() then
        raise exception 'Order not found';
    end if;
    if o.status <> 'delivered' then
        raise exception 'Only delivered orders can be returned';
    end if;
    if o.delivered_at is null
       or o.delivered_at + make_interval(days => s.return_window_days) < now() then
        raise exception 'The % day return window for this order has closed', s.return_window_days;
    end if;
    if coalesce(trim(p_reason), '') = '' then
        raise exception 'Tell us why you are returning this order';
    end if;
    if exists (
        select 1 from public.return_requests
        where order_id = o.id and status in ('requested', 'approved')
    ) then
        raise exception 'A return has already been requested for this order';
    end if;

    if o.total = 0 then
        choice := 'none';
    elsif choice not in ('leaf_points', 'money_refund') then
        choice := 'money_refund';
    end if;

    insert into public.return_requests (order_id, user_id, reason, details, refund_choice)
    values (o.id, o.user_id, trim(p_reason), coalesce(trim(p_details), ''), choice)
    returning * into r;

    perform public._notify(
        o.user_id, 'return_updated', 'Return requested',
        'We received your return request for order #' || upper(left(o.id::text, 8)) ||
        '. We will review it within 48 hours.',
        'ORDER', o.id
    );
    return r;
end;
$$;

create or replace function public.admin_resolve_return(
    p_return_id uuid,
    p_approve boolean,
    p_note text default ''
)
returns public.return_requests
language plpgsql
security definer
set search_path = public
as $$
declare
    r public.return_requests;
    o public.orders;
    short text;
begin
    if not public.is_admin() then
        raise exception 'Admin only';
    end if;

    select * into r from public.return_requests where id = p_return_id for update;
    if not found then
        raise exception 'Return request not found';
    end if;
    if r.status <> 'requested' then
        raise exception 'This return has already been resolved';
    end if;

    select * into o from public.orders where id = r.order_id for update;
    short := upper(left(o.id::text, 8));

    if not p_approve then
        update public.return_requests
        set status = 'rejected',
            admin_note = coalesce(p_note, ''),
            resolved_at = now()
        where id = r.id
        returning * into r;

        perform public._notify(
            o.user_id, 'return_updated', 'Return not approved',
            'Your return request for order #' || short || ' was not approved.' ||
            case when coalesce(p_note, '') <> '' then ' ' || p_note else '' end,
            'ORDER', o.id
        );
        return r;
    end if;

    update public.return_requests
    set status = 'approved',
        admin_note = coalesce(p_note, ''),
        resolved_at = now()
    where id = r.id
    returning * into r;

    perform set_config('kindleaf.status_note', 'Return approved', true);
    update public.orders
    set status = 'returned',
        cancel_refund_choice = r.refund_choice,
        refund_status = case r.refund_choice
            when 'leaf_points' then 'completed'
            when 'money_refund' then 'pending'
            else 'none'
        end,
        refund_amount = case when r.refund_choice = 'none' then 0 else o.total end,
        payment_status = case when r.refund_choice = 'leaf_points' then 'refunded' else payment_status end
    where id = o.id
    returning * into o;
    perform set_config('kindleaf.status_note', '', true);

    if r.refund_choice = 'leaf_points' then
        perform public._apply_points(
            o.user_id, o.total * 10, 'refund', 'Refund for returned order #' || short, o.id
        );
    end if;

    perform public._notify(
        o.user_id, 'return_updated', 'Return approved',
        'Your return for order #' || short || ' was approved.' ||
        case r.refund_choice
            when 'leaf_points' then ' Leaf Points have been credited.'
            when 'money_refund' then ' Your refund is being processed.'
            else ''
        end,
        'ORDER', o.id
    );
    return r;
end;
$$;

-- ════════════════════════════════════════════════════════════════════
-- 16. Support tickets
-- ════════════════════════════════════════════════════════════════════

create table if not exists public.support_tickets (
    id uuid primary key default gen_random_uuid(),
    user_id uuid references auth.users (id) on delete set null default auth.uid(),
    order_id uuid references public.orders (id) on delete set null,
    email text not null default '',
    subject text not null check (char_length(trim(subject)) between 1 and 200),
    message text not null check (char_length(trim(message)) between 1 and 5000),
    status text not null default 'open' check (status in ('open', 'in_progress', 'resolved')),
    admin_note text not null default '',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists support_tickets_status_idx on public.support_tickets (status, created_at desc);

alter table public.support_tickets enable row level security;

drop policy if exists "Users create own tickets" on public.support_tickets;
create policy "Users create own tickets"
    on public.support_tickets for insert
    with check (auth.uid() = user_id and status = 'open' and admin_note = '');

drop policy if exists "Users view own tickets" on public.support_tickets;
create policy "Users view own tickets"
    on public.support_tickets for select
    using (auth.uid() = user_id or public.is_admin());

drop policy if exists "Admins update tickets" on public.support_tickets;
create policy "Admins update tickets"
    on public.support_tickets for update
    using (public.is_admin())
    with check (public.is_admin());

-- ════════════════════════════════════════════════════════════════════
-- 17. Storage: only admins write catalog images; customers write their own review photos
-- ════════════════════════════════════════════════════════════════════

drop policy if exists "Authenticated upload images" on storage.objects;
drop policy if exists "Authenticated update images" on storage.objects;
drop policy if exists "Admins upload images" on storage.objects;
drop policy if exists "Admins update images" on storage.objects;
drop policy if exists "Customers upload review images" on storage.objects;

create policy "Admins upload images"
    on storage.objects for insert
    to authenticated
    with check (bucket_id = 'images' and public.is_admin());

create policy "Admins update images"
    on storage.objects for update
    to authenticated
    using (bucket_id = 'images' and public.is_admin())
    with check (bucket_id = 'images' and public.is_admin());

create policy "Customers upload review images"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'images'
        and (storage.foldername(name))[1] = 'reviews'
        and (storage.foldername(name))[2] = auth.uid()::text
    );

-- ════════════════════════════════════════════════════════════════════
-- 18. Admin analytics
-- ════════════════════════════════════════════════════════════════════

create or replace function public.admin_stats(p_days integer default 30)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    since timestamptz := now() - make_interval(days => greatest(coalesce(p_days, 30), 1));
    result jsonb;
begin
    if not public.is_admin() then
        raise exception 'Admin only';
    end if;

    select jsonb_build_object(
        'days', greatest(coalesce(p_days, 30), 1),
        'revenue', coalesce((
            select sum(total) from public.orders
            where payment_status = 'paid'
              and status not in ('cancelled', 'returned')
              and created_at >= since
        ), 0),
        'orders', (
            select count(*) from public.orders
            where created_at >= since and status <> 'pending_payment'
        ),
        'avg_order_value', coalesce((
            select round(avg(total)) from public.orders
            where created_at >= since and status not in ('cancelled', 'pending_payment', 'returned')
        ), 0),
        'to_fulfil', (select count(*) from public.orders where status in ('placed', 'confirmed', 'packed')),
        'in_transit', (select count(*) from public.orders where status in ('shipped', 'out_for_delivery')),
        'awaiting_payment', (select count(*) from public.orders where status = 'pending_payment'),
        'refunds_pending', (
            select count(*) from public.orders where refund_status in ('pending', 'processing', 'failed')
        ),
        'returns_pending', (select count(*) from public.return_requests where status = 'requested'),
        'open_tickets', (select count(*) from public.support_tickets where status <> 'resolved'),
        'customers', (select count(*) from public.profiles where role = 'customer'),
        'new_customers', (
            select count(*) from public.profiles where role = 'customer' and created_at >= since
        ),
        'products', (select count(*) from public.items),
        'active_products', (select count(*) from public.items where is_active),
        'categories', (select count(*) from public.categories where is_active),
        'low_stock', coalesce((
            select jsonb_agg(
                jsonb_build_object('id', id, 'name', name, 'stock_qty', stock_qty)
                order by stock_qty, name
            )
            from public.items
            where is_active and stock_qty <= low_stock_threshold
        ), '[]'::jsonb),
        'top_products', coalesce((
            select jsonb_agg(t)
            from (
                select oi.name,
                       sum(oi.quantity)::integer as quantity,
                       sum(oi.quantity * oi.price)::integer as revenue
                from public.order_items oi
                join public.orders o on o.id = oi.order_id
                where o.created_at >= since
                  and o.status not in ('cancelled', 'pending_payment', 'returned')
                group by oi.name
                order by 2 desc
                limit 5
            ) t
        ), '[]'::jsonb),
        'daily', coalesce((
            select jsonb_agg(d order by d.day)
            from (
                select to_char(date_trunc('day', created_at), 'YYYY-MM-DD') as day,
                       count(*)::integer as orders,
                       coalesce(sum(total) filter (
                           where payment_status = 'paid' and status not in ('cancelled', 'returned')
                       ), 0)::integer as revenue
                from public.orders
                where created_at >= since and status <> 'pending_payment'
                group by 1
            ) d
        ), '[]'::jsonb),
        'status_counts', coalesce((
            select jsonb_object_agg(status, n)
            from (select status, count(*)::integer as n from public.orders group by status) sc
        ), '{}'::jsonb)
    ) into result;

    return result;
end;
$$;

-- ════════════════════════════════════════════════════════════════════
-- 19. Account deletion (Google Play requirement)
-- ════════════════════════════════════════════════════════════════════

-- Returns why an account can't be deleted yet, or null. The delete-account Edge Function calls this
-- and then removes the user through the Supabase Auth Admin API (the supported way to delete users).
drop function if exists public.delete_own_account();
create or replace function public.account_deletion_blocker(p_user uuid)
returns text
language plpgsql
stable
security definer
set search_path = public
as $$
begin
    if p_user is null then
        return 'Not authenticated';
    end if;
    if exists (select 1 from public.profiles where id = p_user and role = 'admin') then
        return 'Admin accounts must be removed from the Supabase dashboard';
    end if;
    if exists (
        select 1 from public.orders
        where user_id = p_user
          and status in ('pending_payment', 'placed', 'confirmed', 'packed', 'shipped', 'out_for_delivery')
    ) then
        return 'You have orders in progress. Please wait until they are delivered or cancel them first.';
    end if;
    if exists (
        select 1 from public.orders
        where user_id = p_user and refund_status in ('pending', 'processing', 'failed')
    ) then
        return 'A refund is still being processed on your account. Please try again once it completes.';
    end if;
    if exists (select 1 from public.return_requests where user_id = p_user and status = 'requested') then
        return 'You have a return request under review. Please try again once it is resolved.';
    end if;
    return null;
end;
$$;

-- ════════════════════════════════════════════════════════════════════
-- 20. Function permissions
-- ════════════════════════════════════════════════════════════════════

-- Internal helpers: never callable through the API.
revoke execute on function public._notify(uuid, text, text, text, text, uuid) from public, anon, authenticated;
revoke execute on function public._apply_points(uuid, integer, text, text, uuid) from public, anon, authenticated;
revoke execute on function public._price_cart(uuid, jsonb, text, integer) from public, anon, authenticated;
revoke execute on function public._cancel_order(uuid, text, boolean) from public, anon, authenticated;
revoke execute on function public._restock_order(uuid) from public, anon, authenticated;
revoke execute on function public._recompute_item_rating(uuid) from public, anon, authenticated;
revoke execute on function public.expire_unpaid_orders() from public, anon, authenticated;

-- Payment state changes: Edge Functions (service role) only.
revoke execute on function public.payment_attach_razorpay_order(uuid, text) from public, anon, authenticated;
revoke execute on function public.payment_mark_paid(text, text, text, bigint) from public, anon, authenticated;
revoke execute on function public.payment_mark_failed(text, text) from public, anon, authenticated;
revoke execute on function public.payment_reset_for_retry(uuid) from public, anon, authenticated;
revoke execute on function public.payment_set_refund(uuid, text, text) from public, anon, authenticated;
grant execute on function public.payment_attach_razorpay_order(uuid, text) to service_role;
grant execute on function public.payment_mark_paid(text, text, text, bigint) to service_role;
grant execute on function public.payment_mark_failed(text, text) to service_role;
grant execute on function public.payment_reset_for_retry(uuid) to service_role;
grant execute on function public.payment_set_refund(uuid, text, text) to service_role;
grant execute on function public.expire_unpaid_orders() to service_role;
revoke execute on function public.account_deletion_blocker(uuid) from public, anon, authenticated;
grant execute on function public.account_deletion_blocker(uuid) to service_role;

-- Customer + admin RPCs (each checks auth/role internally).
revoke execute on function public.quote_cart(jsonb, text, integer) from public, anon;
revoke execute on function public.place_order(uuid, jsonb, text, text, integer) from public, anon;
revoke execute on function public.switch_order_to_cod(uuid) from public, anon;
revoke execute on function public.cancel_own_order(uuid, text) from public, anon;
revoke execute on function public.request_return(uuid, text, text, text) from public, anon;
revoke execute on function public.admin_cancel_order(uuid, text) from public, anon;
revoke execute on function public.admin_complete_refund(uuid, text) from public, anon;
revoke execute on function public.admin_update_order_status(uuid, text, text, text, text) from public, anon;
revoke execute on function public.admin_adjust_points(uuid, integer, text) from public, anon;
revoke execute on function public.admin_resolve_return(uuid, boolean, text) from public, anon;
revoke execute on function public.admin_stats(integer) from public, anon;

grant execute on function public.quote_cart(jsonb, text, integer) to authenticated;
grant execute on function public.place_order(uuid, jsonb, text, text, integer) to authenticated;
grant execute on function public.switch_order_to_cod(uuid) to authenticated;
grant execute on function public.cancel_own_order(uuid, text) to authenticated;
grant execute on function public.request_return(uuid, text, text, text) to authenticated;
grant execute on function public.admin_cancel_order(uuid, text) to authenticated;
grant execute on function public.admin_complete_refund(uuid, text) to authenticated;
grant execute on function public.admin_update_order_status(uuid, text, text, text, text) to authenticated;
grant execute on function public.admin_adjust_points(uuid, integer, text) to authenticated;
grant execute on function public.admin_resolve_return(uuid, boolean, text) to authenticated;
grant execute on function public.admin_stats(integer) to authenticated;
grant execute on function public.search_items(text, text, integer, integer, text, boolean, integer, integer) to anon, authenticated;

commit;

-- ════════════════════════════════════════════════════════════════════
-- 21. Optional: auto-cancel unpaid online orders every 10 minutes.
-- Requires the pg_cron extension (Dashboard → Database → Extensions).
-- place_order() also expires stale orders, so this is a safety net.
-- ════════════════════════════════════════════════════════════════════

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.schedule(
            'kindleaf-expire-unpaid-orders',
            '*/10 * * * *',
            'select public.expire_unpaid_orders()'
        );
    end if;
exception when others then
    raise notice 'pg_cron schedule skipped: %', sqlerrm;
end $$;

notify pgrst, 'reload schema';
