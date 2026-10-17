-- Notification event toggles (admin on/off per action)
-- Run after 006_order_cancel_refund.sql

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

grant execute on function public.is_notification_event_enabled(text) to authenticated;
grant execute on function public.is_notification_event_enabled(text) to anon;

-- Patch cancel / refund RPCs to respect toggles

create or replace function public.cancel_own_order(
    p_order_id uuid,
    p_refund_choice text default 'none'
)
returns public.orders
language plpgsql
security definer
set search_path = public
as $$
declare
    o public.orders;
    choice text := coalesce(nullif(trim(p_refund_choice), ''), 'none');
    points_to_credit integer;
    is_cod boolean;
    cancel_msg text;
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    select * into o from public.orders where id = p_order_id for update;
    if not found then
        raise exception 'Order not found';
    end if;
    if o.user_id <> auth.uid() then
        raise exception 'Not your order';
    end if;
    if o.status not in ('placed', 'confirmed') then
        raise exception 'Order can no longer be cancelled';
    end if;
    if o.status = 'cancelled' then
        raise exception 'Order already cancelled';
    end if;

    is_cod := public.is_cod_payment(o.payment_method);

    if is_cod then
        choice := 'none';
        update public.orders
        set status = 'cancelled',
            cancel_refund_choice = 'none',
            refund_status = 'none',
            cancelled_at = now(),
            updated_at = now()
        where id = o.id
        returning * into o;
    elsif choice = 'leaf_points' then
        points_to_credit := greatest(o.total, 0) * 10;
        update public.profiles
        set leaf_points = leaf_points + points_to_credit,
            updated_at = now()
        where id = o.user_id;

        update public.orders
        set status = 'cancelled',
            cancel_refund_choice = 'leaf_points',
            refund_status = 'completed',
            cancelled_at = now(),
            updated_at = now()
        where id = o.id
        returning * into o;
    elsif choice = 'money_refund' then
        update public.orders
        set status = 'cancelled',
            cancel_refund_choice = 'money_refund',
            refund_status = 'pending',
            cancelled_at = now(),
            updated_at = now()
        where id = o.id
        returning * into o;
    else
        raise exception 'Choose leaf_points or money_refund for prepaid orders';
    end if;

    if public.is_notification_event_enabled('order_cancelled') then
        cancel_msg := case
            when is_cod then
                'Your order #' || upper(left(o.id::text, 8)) || ' was cancelled.'
            when choice = 'leaf_points' then
                'Your order #' || upper(left(o.id::text, 8)) ||
                ' was cancelled. Leaf Points were credited instantly.'
            else
                'Your order #' || upper(left(o.id::text, 8)) ||
                ' was cancelled. Refund to your payment method will take 24–48 hours.'
        end;
        insert into public.notifications (user_id, title, message, type)
        values (o.user_id, 'Order Cancelled', cancel_msg, 'ORDER');
    end if;

    if choice = 'leaf_points'
       and public.is_notification_event_enabled('leaf_points_added') then
        insert into public.notifications (user_id, title, message, type)
        values (
            o.user_id,
            'Leaf Points Added',
            points_to_credit::text || ' Leaf Points were added to your account.',
            'GENERAL'
        );
    end if;

    return o;
end;
$$;

create or replace function public.admin_complete_refund(p_order_id uuid)
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
    if o.refund_status <> 'pending' then
        raise exception 'No pending refund for this order';
    end if;

    update public.orders
    set refund_status = 'completed',
        updated_at = now()
    where id = o.id
    returning * into o;

    if public.is_notification_event_enabled('refund_completed') then
        insert into public.notifications (user_id, title, message, type)
        values (
            o.user_id,
            'Refund Completed',
            'Your refund for order #' || upper(left(o.id::text, 8)) ||
            ' has been processed to your original payment method.',
            'ORDER'
        );
    end if;

    return o;
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
declare
    o public.orders;
    choice text;
    points_to_credit integer := 0;
    is_cod boolean;
begin
    if not public.is_admin() then
        raise exception 'Admin only';
    end if;

    select * into o from public.orders where id = p_order_id for update;
    if not found then
        raise exception 'Order not found';
    end if;
    if o.status in ('cancelled', 'delivered') then
        raise exception 'Order cannot be cancelled';
    end if;

    is_cod := public.is_cod_payment(o.payment_method);
    if is_cod then
        choice := 'none';
    else
        choice := coalesce(nullif(trim(p_refund_choice), ''), 'money_refund');
        if choice not in ('leaf_points', 'money_refund') then
            choice := 'money_refund';
        end if;
    end if;

    if is_cod then
        update public.orders
        set status = 'cancelled',
            cancel_refund_choice = 'none',
            refund_status = 'none',
            cancelled_at = now(),
            updated_at = now()
        where id = o.id
        returning * into o;
    elsif choice = 'leaf_points' then
        points_to_credit := greatest(o.total, 0) * 10;
        update public.profiles
        set leaf_points = leaf_points + points_to_credit,
            updated_at = now()
        where id = o.user_id;

        update public.orders
        set status = 'cancelled',
            cancel_refund_choice = 'leaf_points',
            refund_status = 'completed',
            cancelled_at = now(),
            updated_at = now()
        where id = o.id
        returning * into o;
    else
        update public.orders
        set status = 'cancelled',
            cancel_refund_choice = 'money_refund',
            refund_status = 'pending',
            cancelled_at = now(),
            updated_at = now()
        where id = o.id
        returning * into o;
    end if;

    if public.is_notification_event_enabled('order_cancelled') then
        insert into public.notifications (user_id, title, message, type)
        values (
            o.user_id,
            'Order Cancelled',
            'Your order #' || upper(left(o.id::text, 8)) || ' was cancelled by Kindleaf.',
            'ORDER'
        );
    end if;

    if choice = 'leaf_points'
       and points_to_credit > 0
       and public.is_notification_event_enabled('leaf_points_added') then
        insert into public.notifications (user_id, title, message, type)
        values (
            o.user_id,
            'Leaf Points Added',
            points_to_credit::text || ' Leaf Points were added to your account.',
            'GENERAL'
        );
    end if;

    return o;
end;
$$;
