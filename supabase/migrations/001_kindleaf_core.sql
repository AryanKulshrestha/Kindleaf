-- Kindleaf core schema: profiles, items, addresses
-- Run in Supabase SQL Editor: https://supabase.com/dashboard/project/_/sql

create extension if not exists "pgcrypto";

-- ── profiles (app user data, 1:1 with auth.users) ──
create table if not exists public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    name text not null default '',
    email text not null default '',
    phone text not null default '',
    leaf_points integer not null default 0,
    avatar_initials text not null default 'U',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- ── items (tea catalog) ──
create table if not exists public.items (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    description text not null default '',
    tags text[] not null default '{}',
    rating real not null default 0,
    review_count integer not null default 0,
    size text not null default '',
    price integer not null default 0,
    image_url text not null default '',
    category text not null default 'All Teas',
    is_active boolean not null default true,
    created_at timestamptz not null default now()
);

-- ── addresses ──
create table if not exists public.addresses (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    label text not null default 'Home',
    name text not null,
    address_line text not null,
    city text not null,
    state text not null,
    pincode text not null,
    phone text not null,
    is_default boolean not null default false,
    created_at timestamptz not null default now()
);

create index if not exists addresses_user_id_idx on public.addresses (user_id);
create index if not exists items_is_active_idx on public.items (is_active);

-- Keep a single default address per user
create or replace function public.ensure_single_default_address()
returns trigger
language plpgsql
as $$
begin
    if new.is_default then
        update public.addresses
        set is_default = false
        where user_id = new.user_id
          and id is distinct from new.id
          and is_default = true;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_single_default_address on public.addresses;
create trigger trg_single_default_address
    before insert or update of is_default on public.addresses
    for each row
    execute function public.ensure_single_default_address();

-- Auto-create profile on signup
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    full_name text;
    initials text;
begin
    full_name := coalesce(
        new.raw_user_meta_data ->> 'name',
        new.raw_user_meta_data ->> 'full_name',
        split_part(new.email, '@', 1),
        'User'
    );
    initials := upper(left(regexp_replace(full_name, '[^A-Za-z ]', '', 'g'), 1));
    if position(' ' in trim(full_name)) > 0 then
        initials := upper(
            left(split_part(trim(full_name), ' ', 1), 1) ||
            left(split_part(trim(full_name), ' ', 2), 1)
        );
    end if;
    if initials is null or initials = '' then
        initials := 'U';
    end if;

    insert into public.profiles (id, name, email, avatar_initials)
    values (new.id, full_name, coalesce(new.email, ''), initials)
    on conflict (id) do update
        set email = excluded.email,
            name = coalesce(nullif(public.profiles.name, ''), excluded.name),
            updated_at = now();

    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row
    execute function public.handle_new_user();

-- ── RLS ──
alter table public.profiles enable row level security;
alter table public.items enable row level security;
alter table public.addresses enable row level security;

drop policy if exists "Profiles are viewable by owner" on public.profiles;
create policy "Profiles are viewable by owner"
    on public.profiles for select
    using (auth.uid() = id);

drop policy if exists "Profiles are insertable by owner" on public.profiles;
create policy "Profiles are insertable by owner"
    on public.profiles for insert
    with check (auth.uid() = id);

drop policy if exists "Profiles are updatable by owner" on public.profiles;
create policy "Profiles are updatable by owner"
    on public.profiles for update
    using (auth.uid() = id)
    with check (auth.uid() = id);

drop policy if exists "Active items are publicly readable" on public.items;
create policy "Active items are publicly readable"
    on public.items for select
    using (is_active = true);

drop policy if exists "Addresses are viewable by owner" on public.addresses;
create policy "Addresses are viewable by owner"
    on public.addresses for select
    using (auth.uid() = user_id);

drop policy if exists "Addresses are insertable by owner" on public.addresses;
create policy "Addresses are insertable by owner"
    on public.addresses for insert
    with check (auth.uid() = user_id);

drop policy if exists "Addresses are updatable by owner" on public.addresses;
create policy "Addresses are updatable by owner"
    on public.addresses for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "Addresses are deletable by owner" on public.addresses;
create policy "Addresses are deletable by owner"
    on public.addresses for delete
    using (auth.uid() = user_id);

-- ── Seed catalog (idempotent by name) ──
insert into public.items (name, description, tags, rating, review_count, size, price, image_url, category)
select v.name, v.description, v.tags, v.rating, v.review_count, v.size, v.price, v.image_url, v.category
from (values
    (
        'Herbal Green Tea',
        'A refreshing blend of premium green tea leaves with natural herbs. Rich in antioxidants and perfect for daily wellness.',
        array['Immunity', 'Detox', 'Energy']::text[],
        4.8::real, 156, '25 Cups | 50g', 199,
        'https://images.unsplash.com/photo-1556679343-c7306c1976bc?w=400',
        'Green Tea'
    ),
    (
        'Tulsi Green Tea',
        'Holy basil infused green tea for stress relief and immunity boost. Made with organic tulsi leaves.',
        array['Immunity', 'Stress Relief']::text[],
        4.7::real, 132, '25 Cups | 50g', 199,
        'https://images.unsplash.com/photo-1564890369478-c89ca6d9cde9?w=400',
        'Green Tea'
    ),
    (
        'Moringa Green Tea',
        'Nutrient-rich moringa blended with green tea for weight management and detox support.',
        array['Detox', 'Weight Management']::text[],
        4.7::real, 98, '25 Cups | 50g', 199,
        'https://images.unsplash.com/photo-1597318181409-cf64d0b5d14e?w=400',
        'Detox'
    ),
    (
        'Lemongrass Ginger Tea',
        'A zesty combination of fresh lemongrass and ginger for improved digestion and a refreshing taste.',
        array['Digestion', 'Refreshing']::text[],
        4.6::real, 87, '25 Cups | 50g', 199,
        'https://images.unsplash.com/photo-1571934811356-5cc061b6821f?w=400',
        'Herbal Tea'
    ),
    (
        'Amla Green Tea',
        'Indian gooseberry enriched green tea for a powerful immunity and antioxidant boost.',
        array['Immunity', 'Antioxidant']::text[],
        4.6::real, 76, '25 Cups | 50g', 199,
        'https://images.unsplash.com/photo-1544787219-7f47ccb76574?w=400',
        'Immunity'
    ),
    (
        'Chamomile Calm Tea',
        'Pure chamomile flowers for a calming, sleep-promoting herbal tea experience.',
        array['Sleep', 'Calm', 'Relaxation']::text[],
        4.5::real, 64, '25 Cups | 50g', 249,
        'https://images.unsplash.com/photo-1576092768241-dec231879fc3?w=400',
        'Relaxation'
    )
) as v(name, description, tags, rating, review_count, size, price, image_url, category)
where not exists (
    select 1 from public.items i where i.name = v.name
);
