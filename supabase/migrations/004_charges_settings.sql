-- Charges settings for Kindleaf (delivery + optional extra fees)
-- Run in Supabase SQL Editor

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
values
    (
        'delivery',
        'Delivery Fee',
        40,
        true,
        299,
        'Flat delivery fee. Waived when cart subtotal reaches the free-above amount.',
        1
    )
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
