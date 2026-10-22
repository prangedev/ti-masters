-- Applied to project "ti-masters" (snbmafhkirlxyrnyzxrt) on 2026-10-19 as three migrations:
-- business_part1_offline_sync, fix_check_same_company, business_fk_indexes (merged here).
--
-- Business part 1: clients, tickets, appointments and time entries.
-- Offline-first sync: ids are generated on the phone (uuid), the server stamps updated_at with
-- clock_timestamp() on every write (the phones pull "changed since"), and rows are never deleted
-- by the app, only marked with deleted_at so the deletion reaches every device.

alter table public.companies add column next_ticket_number integer not null default 1;

create or replace function private.touch_sync_row()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = clock_timestamp();
  if tg_op = 'INSERT' then
    new.created_at = coalesce(new.created_at, clock_timestamp());
  else
    -- Ownership and creation data never change after insert.
    new.company_id = old.company_id;
    new.created_at = old.created_at;
    new.created_by = old.created_by;
  end if;
  return new;
end;
$$;

create table public.clients (
  id uuid primary key,
  company_id uuid not null references public.companies (id) on delete cascade,
  name text not null check (char_length(name) between 1 and 160),
  doc_type text check (doc_type in ('cpf', 'cnpj')),
  doc_number text check (char_length(doc_number) <= 14),
  email text check (char_length(email) <= 254),
  phone text check (char_length(phone) <= 20),
  address text check (char_length(address) <= 300),
  city text check (char_length(city) <= 120),
  notes text check (char_length(notes) <= 4000),
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.tickets (
  id uuid primary key,
  company_id uuid not null references public.companies (id) on delete cascade,
  client_id uuid not null references public.clients (id) on delete cascade,
  number integer,
  title text not null check (char_length(title) between 1 and 200),
  description text check (char_length(description) <= 8000),
  priority text not null default 'media' check (priority in ('baixa', 'media', 'alta', 'urgente')),
  status text not null default 'aberto' check (status in ('aberto', 'em_andamento', 'aguardando', 'resolvido', 'fechado')),
  due_at timestamptz,
  closed_at timestamptz,
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (company_id, number)
);

create table public.appointments (
  id uuid primary key,
  company_id uuid not null references public.companies (id) on delete cascade,
  client_id uuid not null references public.clients (id) on delete cascade,
  ticket_id uuid references public.tickets (id) on delete set null,
  kind text not null default 'visita' check (kind in ('visita', 'remoto')),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  address text check (char_length(address) <= 300),
  notes text check (char_length(notes) <= 4000),
  status text not null default 'agendado' check (status in ('agendado', 'concluido', 'cancelado')),
  reminder_minutes integer check (reminder_minutes between 0 and 10080),
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  check (ends_at >= starts_at)
);

create table public.time_entries (
  id uuid primary key,
  company_id uuid not null references public.companies (id) on delete cascade,
  client_id uuid not null references public.clients (id) on delete cascade,
  ticket_id uuid references public.tickets (id) on delete set null,
  user_id uuid default auth.uid() references auth.users (id) on delete set null,
  started_at timestamptz not null,
  ended_at timestamptz,
  minutes integer generated always as (
    case when ended_at is null then null
         else (extract(epoch from (ended_at - started_at)) / 60)::integer end
  ) stored,
  note text check (char_length(note) <= 2000),
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  check (ended_at is null or ended_at >= started_at)
);

-- Indexes for the incremental pull ("company X, changed after T") and foreign keys.
create index clients_sync_idx on public.clients (company_id, updated_at);
create index tickets_sync_idx on public.tickets (company_id, updated_at);
create index tickets_client_idx on public.tickets (client_id);
create index appointments_sync_idx on public.appointments (company_id, updated_at);
create index appointments_client_idx on public.appointments (client_id);
create index appointments_ticket_idx on public.appointments (ticket_id);
create index time_entries_sync_idx on public.time_entries (company_id, updated_at);
create index time_entries_client_idx on public.time_entries (client_id);
create index time_entries_ticket_idx on public.time_entries (ticket_id);
-- Used when an account is deleted (ON DELETE SET NULL scans these columns).
create index clients_created_by_idx on public.clients (created_by);
create index tickets_created_by_idx on public.tickets (created_by);
create index appointments_created_by_idx on public.appointments (created_by);
create index time_entries_created_by_idx on public.time_entries (created_by);
create index time_entries_user_idx on public.time_entries (user_id);

create trigger clients_touch before insert or update on public.clients
  for each row execute function private.touch_sync_row();
create trigger tickets_touch before insert or update on public.tickets
  for each row execute function private.touch_sync_row();
create trigger appointments_touch before insert or update on public.appointments
  for each row execute function private.touch_sync_row();
create trigger time_entries_touch before insert or update on public.time_entries
  for each row execute function private.touch_sync_row();

-- Ticket numbers are per company and assigned by the server, so offline phones never collide.
-- Upserts fire BEFORE INSERT even when they end up updating, hence the existence check.
create or replace function private.assign_ticket_number()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.number is null and not exists (select 1 from public.tickets t where t.id = new.id) then
    update public.companies
       set next_ticket_number = next_ticket_number + 1
     where id = new.company_id
    returning next_ticket_number - 1 into new.number;
  end if;
  return new;
end;
$$;

create trigger tickets_number before insert on public.tickets
  for each row execute function private.assign_ticket_number();

-- The client and ticket referenced by a row must belong to the same company.
create or replace function private.check_same_company()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  linked_ticket uuid;
begin
  if not exists (select 1 from public.clients c where c.id = new.client_id and c.company_id = new.company_id) then
    raise exception 'client belongs to another company';
  end if;
  -- tickets have no ticket_id column, so the field is read only on the other tables.
  if tg_table_name <> 'tickets' then
    linked_ticket := (to_jsonb(new) ->> 'ticket_id')::uuid;
    if linked_ticket is not null
       and not exists (select 1 from public.tickets t where t.id = linked_ticket and t.company_id = new.company_id) then
      raise exception 'ticket belongs to another company';
    end if;
  end if;
  return new;
end;
$$;

create trigger tickets_same_company before insert or update on public.tickets
  for each row execute function private.check_same_company();
create trigger appointments_same_company before insert or update on public.appointments
  for each row execute function private.check_same_company();
create trigger time_entries_same_company before insert or update on public.time_entries
  for each row execute function private.check_same_company();

-- RLS: members read their company's data; writing requires the Business plan.
alter table public.clients enable row level security;
alter table public.tickets enable row level security;
alter table public.appointments enable row level security;
alter table public.time_entries enable row level security;

do $$
declare
  t text;
begin
  foreach t in array array['clients', 'tickets', 'appointments', 'time_entries'] loop
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

-- Column grants: the server owns number, timestamps and computed values.
revoke insert, update on public.clients, public.tickets, public.appointments, public.time_entries from authenticated;
grant insert (id, company_id, name, doc_type, doc_number, email, phone, address, city, notes, deleted_at),
      update (id, company_id, name, doc_type, doc_number, email, phone, address, city, notes, deleted_at)
  on public.clients to authenticated;
grant insert (id, company_id, client_id, title, description, priority, status, due_at, closed_at, deleted_at),
      update (id, company_id, client_id, title, description, priority, status, due_at, closed_at, deleted_at)
  on public.tickets to authenticated;
grant insert (id, company_id, client_id, ticket_id, kind, starts_at, ends_at, address, notes, status, reminder_minutes, deleted_at),
      update (id, company_id, client_id, ticket_id, kind, starts_at, ends_at, address, notes, status, reminder_minutes, deleted_at)
  on public.appointments to authenticated;
grant insert (id, company_id, client_id, ticket_id, user_id, started_at, ended_at, note, deleted_at),
      update (id, company_id, client_id, ticket_id, user_id, started_at, ended_at, note, deleted_at)
  on public.time_entries to authenticated;

revoke all on function private.touch_sync_row() from public;
revoke all on function private.assign_ticket_number() from public;
revoke all on function private.check_same_company() from public;

-- The app needs to know which company to sync.
create or replace function public.get_my_company()
returns uuid
language sql
stable
security invoker
set search_path = ''
as $$
  select m.company_id
    from public.company_members m
   where m.user_id = (select auth.uid())
   order by (m.role = 'owner') desc, m.created_at
   limit 1;
$$;

revoke all on function public.get_my_company() from public, anon;
grant execute on function public.get_my_company() to authenticated;
