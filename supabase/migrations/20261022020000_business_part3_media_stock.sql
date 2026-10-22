-- Business part 3: photos (before/after) and the client's signature on documents, parts stock
-- and the data the monthly dashboard needs. Same offline-first rules as parts 1 and 2.
-- Files live in the private bucket "order-media" under "<company_id>/<order_id>/...".

-- Stock control per catalog product; cost_cents feeds the gross profit on the dashboard.
alter table public.service_items
  add column track_stock boolean not null default false,
  add column min_stock numeric(12, 3) not null default 0 check (min_stock between 0 and 1000000),
  add column cost_cents bigint not null default 0 check (cost_cents between 0 and 100000000000);

grant insert (track_stock, min_stock, cost_cents), update (track_stock, min_stock, cost_cents)
  on public.service_items to authenticated;

-- When the service order was concluded (dashboard month) and who signed it.
alter table public.service_orders
  add column completed_at timestamptz,
  add column signature_path text check (char_length(signature_path) <= 300),
  add column signed_by text check (char_length(signed_by) <= 160),
  add column signed_at timestamptz;

grant insert (completed_at, signature_path, signed_by, signed_at),
      update (completed_at, signature_path, signed_by, signed_at)
  on public.service_orders to authenticated;

-- Photos taken before and after the service.
create table public.order_photos (
  id uuid primary key,
  company_id uuid not null references public.companies (id) on delete cascade,
  order_id uuid not null references public.service_orders (id) on delete cascade,
  stage text not null check (stage in ('antes', 'depois')),
  storage_path text check (char_length(storage_path) <= 300),
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

-- Stock is the sum of the movements (never a stored counter), so phones that worked offline
-- never overwrite each other. Parts used by a concluded service order use a movement id derived
-- from the order and the product, so concluding it on two phones counts the parts only once.
create table public.stock_movements (
  id uuid primary key,
  company_id uuid not null references public.companies (id) on delete cascade,
  item_id uuid not null references public.service_items (id) on delete cascade,
  order_id uuid references public.service_orders (id) on delete set null,
  kind text not null check (kind in ('entrada', 'saida', 'ajuste', 'os')),
  quantity numeric(12, 3) not null check (quantity between -1000000 and 1000000),
  unit_cost_cents bigint not null default 0 check (unit_cost_cents between 0 and 100000000000),
  note text check (char_length(note) <= 300),
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create index order_photos_sync_idx on public.order_photos (company_id, updated_at);
create index order_photos_order_idx on public.order_photos (order_id);
create index order_photos_created_by_idx on public.order_photos (created_by);
create index stock_movements_sync_idx on public.stock_movements (company_id, updated_at);
create index stock_movements_item_idx on public.stock_movements (item_id);
create index stock_movements_order_idx on public.stock_movements (order_id);
create index stock_movements_created_by_idx on public.stock_movements (created_by);

create trigger order_photos_touch before insert or update on public.order_photos
  for each row execute function private.touch_sync_row();
create trigger stock_movements_touch before insert or update on public.stock_movements
  for each row execute function private.touch_sync_row();

-- Linked rows must belong to the same company.
create or replace function private.check_media_stock_company()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  linked_order uuid := (to_jsonb(new) ->> 'order_id')::uuid;
  linked_item uuid := (to_jsonb(new) ->> 'item_id')::uuid;
begin
  if linked_order is not null and not exists (
    select 1 from public.service_orders o where o.id = linked_order and o.company_id = new.company_id
  ) then
    raise exception 'service order belongs to another company';
  end if;
  if linked_item is not null and not exists (
    select 1 from public.service_items i where i.id = linked_item and i.company_id = new.company_id
  ) then
    raise exception 'catalog item belongs to another company';
  end if;
  return new;
end;
$$;

revoke all on function private.check_media_stock_company() from public;

create trigger order_photos_same_company before insert or update on public.order_photos
  for each row execute function private.check_media_stock_company();
create trigger stock_movements_same_company before insert or update on public.stock_movements
  for each row execute function private.check_media_stock_company();

-- RLS: members read, Business plan writes (same as parts 1 and 2).
alter table public.order_photos enable row level security;
alter table public.stock_movements enable row level security;

do $$
declare
  t text;
begin
  foreach t in array array['order_photos', 'stock_movements'] loop
    execute format(
      'create policy "Members read" on public.%I for select to authenticated using (private.is_company_member(company_id))', t);
    execute format(
      'create policy "Business members insert" on public.%I for insert to authenticated with check (private.is_company_member(company_id) and private.company_has_plan(company_id, ''business''))', t);
    execute format(
      'create policy "Business members update" on public.%I for update to authenticated using (private.is_company_member(company_id)) with check (private.is_company_member(company_id) and private.company_has_plan(company_id, ''business''))', t);
    execute format('revoke all on public.%I from anon', t);
    execute format('revoke delete, truncate on public.%I from authenticated', t);
  end loop;
end;
$$;

revoke insert, update on public.order_photos, public.stock_movements from authenticated;
grant insert (id, company_id, order_id, stage, storage_path, deleted_at),
      update (id, company_id, order_id, stage, storage_path, deleted_at)
  on public.order_photos to authenticated;
grant insert (id, company_id, item_id, order_id, kind, quantity, unit_cost_cents, note, deleted_at),
      update (id, company_id, item_id, order_id, kind, quantity, unit_cost_cents, note, deleted_at)
  on public.stock_movements to authenticated;

-- Storage: photos (JPEG) and signatures (PNG) of documents.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('order-media', 'order-media', false, 3145728, array['image/jpeg', 'image/png'])
on conflict (id) do nothing;

create policy "Members read order media" on storage.objects
  for select to authenticated
  using (bucket_id = 'order-media' and private.is_company_member(private.folder_company(name)));

create policy "Business members upload order media" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'order-media'
    and private.is_company_member(private.folder_company(name))
    and private.company_has_plan(private.folder_company(name), 'business')
  );

create policy "Business members replace order media" on storage.objects
  for update to authenticated
  using (bucket_id = 'order-media' and private.is_company_member(private.folder_company(name)))
  with check (
    bucket_id = 'order-media'
    and private.is_company_member(private.folder_company(name))
    and private.company_has_plan(private.folder_company(name), 'business')
  );

-- Owners may also delete without a plan (account deletion after the subscription ended).
create policy "Business members or owners delete order media" on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'order-media'
    and private.is_company_member(private.folder_company(name))
    and (
      private.company_has_plan(private.folder_company(name), 'business')
      or private.has_company_role(private.folder_company(name), array['owner'])
    )
  );
