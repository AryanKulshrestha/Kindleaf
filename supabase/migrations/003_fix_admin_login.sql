-- Kindleaf: apply admin role + commerce tables (run once in SQL Editor)
-- Project: oatrsghtbddihgsacdfq
-- Promotes admin@kindleaf.com (if that Auth user exists) to the admin role.
-- Set its password in Dashboard → Authentication; never store passwords here.

create extension if not exists "pgcrypto";

-- ── Role ──
alter table public.profiles
    add column if not exists role text not null default 'customer';

alter table public.profiles drop constraint if exists profiles_role_check;
alter table public.profiles
    add constraint profiles_role_check check (role in ('customer', 'admin'));

create or replace function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.profiles p
        where p.id = auth.uid() and p.role = 'admin'
    );
$$;

-- Promote existing Auth admin user
update public.profiles
set role = 'admin',
    name = 'Kindleaf Admin',
    avatar_initials = 'KA',
    updated_at = now()
where email = 'admin@kindleaf.com'
   or id in (select id from auth.users where email = 'admin@kindleaf.com');

insert into public.profiles (id, name, email, avatar_initials, role, leaf_points)
select u.id, 'Kindleaf Admin', 'admin@kindleaf.com', 'KA', 'admin', 0
from auth.users u
where u.email = 'admin@kindleaf.com'
on conflict (id) do update
    set role = 'admin',
        email = excluded.email,
        updated_at = now();

-- ── Categories ──
create table if not exists public.categories (
    id uuid primary key default gen_random_uuid(),
    name text not null unique,
    sort_order integer not null default 0,
    is_active boolean not null default true,
    image_url text not null default '',
    created_at timestamptz not null default now()
);

insert into public.categories (name, sort_order, is_active)
select v.name, v.sort_order, true
from (values
    ('Green Tea', 1), ('Detox', 2), ('Herbal Tea', 3), ('Immunity', 4), ('Relaxation', 5)
) as v(name, sort_order)
where not exists (select 1 from public.categories c where c.name = v.name);

-- ── Orders ──
create table if not exists public.orders (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    status text not null default 'placed'
        check (status in ('placed','confirmed','packed','shipped','out_for_delivery','delivered','cancelled')),
    subtotal integer not null default 0,
    delivery_fee integer not null default 0,
    discount integer not null default 0,
    total integer not null default 0,
    payment_method text not null default 'Cash on Delivery',
    delivery_name text not null default '',
    delivery_address_line text not null default '',
    delivery_city text not null default '',
    delivery_state text not null default '',
    delivery_pincode text not null default '',
    delivery_phone text not null default '',
    coupon_code text,
    estimated_delivery text not null default '',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.order_items (
    id uuid primary key default gen_random_uuid(),
    order_id uuid not null references public.orders (id) on delete cascade,
    item_id uuid references public.items (id) on delete set null,
    name text not null,
    price integer not null default 0,
    quantity integer not null default 1,
    image_url text not null default '',
    size text not null default ''
);

create index if not exists orders_user_id_idx on public.orders (user_id);
create index if not exists order_items_order_id_idx on public.order_items (order_id);

-- ── Coupons ──
create table if not exists public.coupons (
    id uuid primary key default gen_random_uuid(),
    type text not null default 'DISCOUNT' check (type in ('DISCOUNT','SHIPPING','SPECIAL')),
    headline text not null default '',
    description text not null default '',
    code text not null unique,
    valid_until date,
    discount_percent integer not null default 0,
    is_active boolean not null default true,
    created_at timestamptz not null default now()
);

insert into public.coupons (type, headline, description, code, valid_until, discount_percent, is_active)
select v.type, v.headline, v.description, v.code, v.valid_until::date, v.discount_percent, true
from (values
    ('DISCOUNT', '15% OFF', 'Get 15% off on orders above ₹499', 'KND15', '2026-12-31', 15),
    ('DISCOUNT', '10% OFF', 'Flat 10% off on all teas', 'KND10', '2026-12-31', 10),
    ('SHIPPING', 'FREE SHIPPING', 'Free delivery on any order', 'KNDFREE', '2026-12-31', 0),
    ('SPECIAL', 'SPECIAL DEAL', 'Exclusive Kindleaf member offer', 'KNDSPECIAL', '2026-12-31', 20)
) as v(type, headline, description, code, valid_until, discount_percent)
where not exists (select 1 from public.coupons c where c.code = v.code);

-- ── Reviews ──
create table if not exists public.reviews (
    id uuid primary key default gen_random_uuid(),
    item_id uuid not null references public.items (id) on delete cascade,
    user_id uuid references auth.users (id) on delete set null,
    author text not null default '',
    author_initial text not null default 'U',
    is_verified boolean not null default false,
    rating integer not null default 5 check (rating between 1 and 5),
    text text not null default '',
    image_urls text[] not null default '{}',
    helpful_count integer not null default 0,
    unhelpful_count integer not null default 0,
    is_visible boolean not null default true,
    created_at timestamptz not null default now()
);

-- ── Notifications ──
create table if not exists public.notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid references auth.users (id) on delete cascade,
    title text not null,
    message text not null,
    type text not null default 'GENERAL' check (type in ('ORDER','PROMO','GENERAL')),
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);

-- ── RLS ──
alter table public.categories enable row level security;
alter table public.orders enable row level security;
alter table public.order_items enable row level security;
alter table public.coupons enable row level security;
alter table public.reviews enable row level security;
alter table public.notifications enable row level security;

drop policy if exists "Admins can view all profiles" on public.profiles;
create policy "Admins can view all profiles"
    on public.profiles for select
    using (public.is_admin() or auth.uid() = id);

drop policy if exists "Admins can update profiles" on public.profiles;
create policy "Admins can update profiles"
    on public.profiles for update
    using (public.is_admin() or auth.uid() = id)
    with check (public.is_admin() or auth.uid() = id);

drop policy if exists "Active items are publicly readable" on public.items;
create policy "Active items are publicly readable"
    on public.items for select
    using (is_active = true or public.is_admin());

drop policy if exists "Admins manage items" on public.items;
create policy "Admins manage items"
    on public.items for all
    using (public.is_admin())
    with check (public.is_admin());

drop policy if exists "Active categories are readable" on public.categories;
create policy "Active categories are readable"
    on public.categories for select
    using (is_active = true or public.is_admin());

drop policy if exists "Admins manage categories" on public.categories;
create policy "Admins manage categories"
    on public.categories for all
    using (public.is_admin())
    with check (public.is_admin());

drop policy if exists "Users view own orders" on public.orders;
create policy "Users view own orders"
    on public.orders for select
    using (auth.uid() = user_id or public.is_admin());

drop policy if exists "Users insert own orders" on public.orders;
create policy "Users insert own orders"
    on public.orders for insert
    with check (auth.uid() = user_id or public.is_admin());

drop policy if exists "Admins update orders" on public.orders;
create policy "Admins update orders"
    on public.orders for update
    using (public.is_admin())
    with check (public.is_admin());

drop policy if exists "Admins delete orders" on public.orders;
create policy "Admins delete orders"
    on public.orders for delete
    using (public.is_admin());

drop policy if exists "Users view own order items" on public.order_items;
create policy "Users view own order items"
    on public.order_items for select
    using (
        public.is_admin()
        or exists (select 1 from public.orders o where o.id = order_id and o.user_id = auth.uid())
    );

drop policy if exists "Users insert own order items" on public.order_items;
create policy "Users insert own order items"
    on public.order_items for insert
    with check (
        public.is_admin()
        or exists (select 1 from public.orders o where o.id = order_id and o.user_id = auth.uid())
    );

drop policy if exists "Admins manage order items" on public.order_items;
create policy "Admins manage order items"
    on public.order_items for all
    using (public.is_admin())
    with check (public.is_admin());

drop policy if exists "Active coupons are readable" on public.coupons;
create policy "Active coupons are readable"
    on public.coupons for select
    using (is_active = true or public.is_admin());

drop policy if exists "Admins manage coupons" on public.coupons;
create policy "Admins manage coupons"
    on public.coupons for all
    using (public.is_admin())
    with check (public.is_admin());

drop policy if exists "Visible reviews are readable" on public.reviews;
create policy "Visible reviews are readable"
    on public.reviews for select
    using (is_visible = true or public.is_admin() or auth.uid() = user_id);

drop policy if exists "Users insert reviews" on public.reviews;
create policy "Users insert reviews"
    on public.reviews for insert
    with check (auth.uid() = user_id or public.is_admin());

drop policy if exists "Admins manage reviews" on public.reviews;
create policy "Admins manage reviews"
    on public.reviews for all
    using (public.is_admin())
    with check (public.is_admin());

drop policy if exists "Users view own notifications" on public.notifications;
create policy "Users view own notifications"
    on public.notifications for select
    using (user_id is null or auth.uid() = user_id or public.is_admin());

drop policy if exists "Users update own notifications" on public.notifications;
create policy "Users update own notifications"
    on public.notifications for update
    using (auth.uid() = user_id or public.is_admin())
    with check (auth.uid() = user_id or public.is_admin());

drop policy if exists "Users insert own notifications" on public.notifications;
create policy "Users insert own notifications"
    on public.notifications for insert
    with check (auth.uid() = user_id or public.is_admin());

drop policy if exists "Admins manage notifications" on public.notifications;
create policy "Admins manage notifications"
    on public.notifications for all
    using (public.is_admin())
    with check (public.is_admin());

-- Verify
select id, email, name, role from public.profiles where email = 'admin@kindleaf.com';
