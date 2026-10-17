-- Public images bucket for product / category / review uploads
-- Run in Supabase SQL Editor (or via MCP) after 004_charges_settings.sql

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'images',
    'images',
    true,
    5242880, -- 5 MB
    array['image/jpeg', 'image/png', 'image/webp', 'image/gif']
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

-- Public can read
drop policy if exists "Public read images" on storage.objects;
create policy "Public read images"
    on storage.objects for select
    using (bucket_id = 'images');

-- Signed-in users (admin panel) can upload / replace
drop policy if exists "Authenticated upload images" on storage.objects;
create policy "Authenticated upload images"
    on storage.objects for insert
    to authenticated
    with check (bucket_id = 'images');

drop policy if exists "Authenticated update images" on storage.objects;
create policy "Authenticated update images"
    on storage.objects for update
    to authenticated
    using (bucket_id = 'images')
    with check (bucket_id = 'images');

-- Admins can delete
drop policy if exists "Admins delete images" on storage.objects;
create policy "Admins delete images"
    on storage.objects for delete
    to authenticated
    using (bucket_id = 'images' and public.is_admin());
