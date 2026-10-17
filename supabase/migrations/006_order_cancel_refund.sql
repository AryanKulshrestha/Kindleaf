-- Order cancel + refund choices (COD vs prepaid)
-- Run in Supabase SQL Editor after 005_storage_images.sql

alter table public.orders
    add column if not exists cancelled_at timestamptz,
    add column if not exists cancel_refund_choice text not null default 'none',
    add column if not exists refund_status text not null default 'none';

alter table public.orders drop constraint if exists orders_cancel_refund_choice_check;
alter table public.orders
    add constraint orders_cancel_refund_choice_check
    check (cancel_refund_choice in ('none', 'leaf_points', 'money_refund'));

alter table public.orders drop constraint if exists orders_refund_status_check;
alter table public.orders
    add constraint orders_refund_status_check
    check (refund_status in ('none', 'pending', 'completed'));

create or replace function public.is_cod_payment(method text)
returns boolean
language sql
immutable
as $$
    select lower(coalesce(method, '')) like '%cash on delivery%'
        or lower(coalesce(method, '')) ~ '(^|[^a-z])cod([^a-z]|$)';
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
declare
    o public.orders;
    choice text := coalesce(nullif(trim(p_refund_choice), ''), 'none');
    points_to_credit integer;
    is_cod boolean;
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
        -- 1 Leaf Point = ₹0.10 → points = total * 10
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

    insert into public.notifications (user_id, title, message, type)
    values (
        o.user_id,
        'Order Cancelled',
        case
            when is_cod then
                'Your order #' || upper(left(o.id::text, 8)) || ' was cancelled.'
            when choice = 'leaf_points' then
                'Your order #' || upper(left(o.id::text, 8)) ||
                ' was cancelled. Leaf Points were credited instantly.'
            else
                'Your order #' || upper(left(o.id::text, 8)) ||
                ' was cancelled. Refund to your payment method will take 24–48 hours.'
        end,
        'ORDER'
    );

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

    insert into public.notifications (user_id, title, message, type)
    values (
        o.user_id,
        'Refund Completed',
        'Your refund for order #' || upper(left(o.id::text, 8)) ||
        ' has been processed to your original payment method.',
        'ORDER'
    );

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
    points_to_credit integer;
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

    insert into public.notifications (user_id, title, message, type)
    values (
        o.user_id,
        'Order Cancelled',
        'Your order #' || upper(left(o.id::text, 8)) || ' was cancelled by Kindleaf.',
        'ORDER'
    );

    return o;
end;
$$;

grant execute on function public.cancel_own_order(uuid, text) to authenticated;
grant execute on function public.admin_complete_refund(uuid) to authenticated;
grant execute on function public.admin_cancel_order(uuid, text) to authenticated;
