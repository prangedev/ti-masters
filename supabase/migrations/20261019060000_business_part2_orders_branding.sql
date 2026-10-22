-- Business part 2: service catalog, pricing settings, quotes and service orders, company branding.
-- Same offline-first rules as part 1 (phone-generated uuids, server-stamped updated_at, soft delete).
-- Files live in private Storage buckets under "<company_id>/...".

-- Company profile shown on quotes and service orders.
alter table public.companies
  add column phone text check (char_length(phone) <= 20),
  add column email text check (char_length(email) <= 254),
  add column address text check (char_length(address) <= 300),
  add column city text check (char_length(city) <= 120),
  add column next_quote_number integer not null default 1,
  add column next_order_number integer not null default 1;

grant update (phone, email, address, city, logo_path) on public.companies to authenticated;

-- Catalog of services and products with their prices (in cents).
create table public.service_items (
  id uuid primary key,
  company_id uuid not null references public.companies (id) on delete cascade,
  name text not null check (char_length(name) between 1 and 160),
  description text check (char_length(description) <= 1000),
  kind text not null default 'servico' check (kind in ('servico', 'produto')),
  unit text not null default 'un' check (char_length(unit) between 1 and 10),
  price_cents bigint not null default 0 check (price_cents between 0 and 100000000000),
  active boolean not null default true,
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

-- One row per company; the app uses the company id as the row id so offline phones never
-- create two rows.
create table public.pricing_settings (
  id uuid primary key,
  company_id uuid not null unique references public.companies (id) on delete cascade,
  hourly_rate_cents bigint not null default 0 check (hourly_rate_cents between 0 and 100000000),
  minimum_charge_cents bigint not null default 0 check (minimum_charge_cents between 0 and 100000000),
  travel_fee_cents bigint not null default 0 check (travel_fee_cents between 0 and 100000000),
  urgency_percent integer not null default 0 check (urgency_percent between 0 and 300),
  quote_validity_days integer not null default 15 check (quote_validity_days between 1 and 365),
  payment_terms text check (char_length(payment_terms) <= 500),
  footer_notes text check (char_length(footer_notes) <= 1000),
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  check (id = company_id)
);

-- Quotes ("orcamento") and service orders ("os"). Items and prices are a snapshot, so later
-- catalog changes never alter a document already sent.
create table public.service_orders (
  id uuid primary key,
  company_id uuid not null references public.companies (id) on delete cascade,
  client_id uuid not null references public.clients (id) on delete cascade,
  ticket_id uuid references public.tickets (id) on delete set null,
  source_quote_id uuid references public.service_orders (id) on delete set null,
  kind text not null check (kind in ('orcamento', 'os')),
  number integer,
  status text not null,
  items jsonb not null default '[]' check (jsonb_typeof(items) = 'array' and jsonb_array_length(items) <= 200),
  items_cents bigint not null default 0 check (items_cents >= 0),
  labor_minutes integer not null default 0 check (labor_minutes between 0 and 100000),
  hourly_rate_cents bigint not null default 0 check (hourly_rate_cents >= 0),
  labor_cents bigint not null default 0 check (labor_cents >= 0),
  urgency_percent integer not null default 0 check (urgency_percent between 0 and 300),
  urgency_cents bigint not null default 0 check (urgency_cents >= 0),
  travel_fee_cents bigint not null default 0 check (travel_fee_cents >= 0),
  discount_cents bigint not null default 0 check (discount_cents >= 0),
  total_cents bigint not null default 0 check (total_cents >= 0),
  notes text check (char_length(notes) <= 4000),
  valid_until timestamptz,
  pdf_path text check (char_length(pdf_path) <= 300),
  sent_at timestamptz,
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (company_id, kind, number),
  check (
    (kind = 'orcamento' and status in ('rascunho', 'enviado', 'aprovado', 'recusado'))
    or (kind = 'os' and status in ('aberta', 'em_execucao', 'concluida', 'cancelada'))
  )
);

create index service_items_sync_idx on public.service_items (company_id, updated_at);
create index pricing_settings_sync_idx on public.pricing_settings (company_id, updated_at);
create index service_orders_sync_idx on public.service_orders (company_id, updated_at);
create index service_orders_client_idx on public.service_orders (client_id);
create index service_orders_ticket_idx on public.service_orders (ticket_id);
create index service_orders_source_quote_idx on public.service_orders (source_quote_id);
create index service_items_created_by_idx on public.service_items (created_by);
create index pricing_settings_created_by_idx on public.pricing_settings (created_by);
create index service_orders_created_by_idx on public.service_orders (created_by);

create trigger service_items_touch before insert or update on public.service_items
  for each row execute function private.touch_sync_row();
create trigger pricing_settings_touch before insert or update on public.pricing_settings
  for each row execute function private.touch_sync_row();
create trigger service_orders_touch before insert or update on public.service_orders
  for each row execute function private.touch_sync_row();
create trigger service_orders_same_company before insert or update on public.service_orders
  for each row execute function private.check_same_company();

-- Numbers per company and per kind, assigned by the server (offline phones never collide).
create or replace function private.assign_order_number()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.number is null and not exists (select 1 from public.service_orders o where o.id = new.id) then
    if new.kind = 'orcamento' then
      update public.companies set next_quote_number = next_quote_number + 1
       where id = new.company_id returning next_quote_number - 1 into new.number;
    else
      update public.companies set next_order_number = next_order_number + 1
       where id = new.company_id returning next_order_number - 1 into new.number;
    end if;
  end if;
  return new;
end;
$$;

create trigger service_orders_number before insert on public.service_orders
  for each row execute function private.assign_order_number();

-- A service order created from a quote must reference a quote of the same company.
create or replace function private.check_source_quote()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.source_quote_id is not null and not exists (
    select 1 from public.service_orders q
     where q.id = new.source_quote_id and q.company_id = new.company_id and q.kind = 'orcamento'
  ) then
    raise exception 'source quote belongs to another company';
  end if;
  return new;
end;
$$;

create trigger service_orders_source_quote before insert or update on public.service_orders
  for each row execute function private.check_source_quote();

revoke all on function private.assign_order_number() from public;
revoke all on function private.check_source_quote() from public;

-- RLS: same rules as part 1 (members read, Business plan writes).
alter table public.service_items enable row level security;
alter table public.pricing_settings enable row level security;
alter table public.service_orders enable row level security;

do $$
declare
  t text;
begin
  foreach t in array array['service_items', 'pricing_settings', 'service_orders'] loop
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

revoke insert, update on public.service_items, public.pricing_settings, public.service_orders from authenticated;
grant insert (id, company_id, name, description, kind, unit, price_cents, active, deleted_at),
      update (id, company_id, name, description, kind, unit, price_cents, active, deleted_at)
  on public.service_items to authenticated;
grant insert (id, company_id, hourly_rate_cents, minimum_charge_cents, travel_fee_cents, urgency_percent,
              quote_validity_days, payment_terms, footer_notes, deleted_at),
      update (id, company_id, hourly_rate_cents, minimum_charge_cents, travel_fee_cents, urgency_percent,
              quote_validity_days, payment_terms, footer_notes, deleted_at)
  on public.pricing_settings to authenticated;
grant insert (id, company_id, client_id, ticket_id, source_quote_id, kind, status, items, items_cents, labor_minutes,
              hourly_rate_cents, labor_cents, urgency_percent, urgency_cents, travel_fee_cents, discount_cents,
              total_cents, notes, valid_until, pdf_path, sent_at, deleted_at),
      update (id, company_id, client_id, ticket_id, source_quote_id, kind, status, items, items_cents, labor_minutes,
              hourly_rate_cents, labor_cents, urgency_percent, urgency_cents, travel_fee_cents, discount_cents,
              total_cents, notes, valid_until, pdf_path, sent_at, deleted_at)
  on public.service_orders to authenticated;

-- Storage: private buckets; the first folder of every file is the company id.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('logos', 'logos', false, 1048576, array['image/png', 'image/jpeg']),
       ('service-orders', 'service-orders', false, 10485760, array['application/pdf'])
on conflict (id) do nothing;

create or replace function private.folder_company(object_name text)
returns uuid
language sql
immutable
set search_path = ''
as $$
  select case
    when split_part(object_name, '/', 1) ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    then split_part(object_name, '/', 1)::uuid
  end;
$$;

revoke all on function private.folder_company(text) from public;
grant execute on function private.folder_company(text) to authenticated;

create policy "Members read company files" on storage.objects
  for select to authenticated
  using (bucket_id in ('logos', 'service-orders') and private.is_company_member(private.folder_company(name)));

create policy "Business members upload company files" on storage.objects
  for insert to authenticated
  with check (
    bucket_id in ('logos', 'service-orders')
    and private.is_company_member(private.folder_company(name))
    and private.company_has_plan(private.folder_company(name), 'business')
  );

create policy "Business members replace company files" on storage.objects
  for update to authenticated
  using (bucket_id in ('logos', 'service-orders') and private.is_company_member(private.folder_company(name)))
  with check (
    bucket_id in ('logos', 'service-orders')
    and private.is_company_member(private.folder_company(name))
    and private.company_has_plan(private.folder_company(name), 'business')
  );

create policy "Business members delete company files" on storage.objects
  for delete to authenticated
  using (
    bucket_id in ('logos', 'service-orders')
    and private.is_company_member(private.folder_company(name))
    and private.company_has_plan(private.folder_company(name), 'business')
  );
