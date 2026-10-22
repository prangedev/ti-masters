-- Applied to project "ti-masters" (snbmafhkirlxyrnyzxrt, sa-east-1) on 2026-10-19.

-- Helpers live in a schema that PostgREST does not expose.
create schema if not exists private;
grant usage on schema private to authenticated;

create or replace function private.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

-- Profiles: one row per auth user.
create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  full_name text not null default '' check (char_length(full_name) <= 120),
  phone text check (char_length(phone) <= 20),
  accepted_terms_at timestamptz,
  accepted_terms_version text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Companies: every user gets a personal company on signup.
create table public.companies (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 120),
  cnpj_cpf text check (char_length(cnpj_cpf) <= 14),
  owner_id uuid not null references auth.users (id) on delete cascade,
  primary_color text check (primary_color ~ '^#[0-9A-Fa-f]{6}$'),
  secondary_color text check (secondary_color ~ '^#[0-9A-Fa-f]{6}$'),
  logo_path text,
  card_slug text unique check (card_slug ~ '^[a-z0-9-]{3,40}$'),
  card_public boolean not null default false,
  card_json jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index companies_owner_id_idx on public.companies (owner_id);

create table public.company_members (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references public.companies (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  role text not null check (role in ('owner', 'admin', 'tech')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (company_id, user_id)
);
create index company_members_user_id_idx on public.company_members (user_id);

create trigger profiles_updated_at before update on public.profiles
  for each row execute function private.set_updated_at();
create trigger companies_updated_at before update on public.companies
  for each row execute function private.set_updated_at();
create trigger company_members_updated_at before update on public.company_members
  for each row execute function private.set_updated_at();

-- Membership helpers used by RLS (security definer avoids recursive RLS on company_members).
create or replace function private.is_company_member(target_company_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.company_members m
    where m.company_id = target_company_id and m.user_id = (select auth.uid())
  );
$$;

create or replace function private.has_company_role(target_company_id uuid, allowed_roles text[])
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.company_members m
    where m.company_id = target_company_id
      and m.user_id = (select auth.uid())
      and m.role = any (allowed_roles)
  );
$$;

revoke all on function private.is_company_member(uuid) from public;
revoke all on function private.has_company_role(uuid, text[]) from public;
grant execute on function private.is_company_member(uuid) to authenticated;
grant execute on function private.has_company_role(uuid, text[]) to authenticated;

-- Signup: create profile, personal company and owner membership.
-- Terms are recorded only when the app sends the accepted version in the signup metadata.
create or replace function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  display_name text := left(coalesce(nullif(trim(new.raw_user_meta_data ->> 'full_name'), ''), split_part(new.email, '@', 1)), 120);
  terms_version text := nullif(trim(new.raw_user_meta_data ->> 'accepted_terms_version'), '');
  new_company_id uuid;
begin
  insert into public.profiles (id, full_name, accepted_terms_at, accepted_terms_version)
  values (new.id, display_name, case when terms_version is null then null else now() end, terms_version);

  insert into public.companies (name, owner_id)
  values (display_name, new.id)
  returning id into new_company_id;

  insert into public.company_members (company_id, user_id, role)
  values (new_company_id, new.id, 'owner');

  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function private.handle_new_user();

-- Row-Level Security.
alter table public.profiles enable row level security;
alter table public.companies enable row level security;
alter table public.company_members enable row level security;

create policy "Users read own profile" on public.profiles
  for select to authenticated using ((select auth.uid()) = id);
create policy "Users update own profile" on public.profiles
  for update to authenticated using ((select auth.uid()) = id) with check ((select auth.uid()) = id);

create policy "Members read their companies" on public.companies
  for select to authenticated using (private.is_company_member(id));
create policy "Owners and admins update their companies" on public.companies
  for update to authenticated
  using (private.has_company_role(id, array['owner', 'admin']))
  with check (private.has_company_role(id, array['owner', 'admin']));

create policy "Members read their company's members" on public.company_members
  for select to authenticated using (private.is_company_member(company_id));

-- Column-level grants: clients may edit only user-owned fields; terms go through accept_terms().
revoke insert, update, delete on public.profiles from anon, authenticated;
revoke insert, update, delete on public.companies from anon, authenticated;
revoke insert, update, delete on public.company_members from anon, authenticated;
revoke select on public.profiles, public.companies, public.company_members from anon;
grant update (full_name, phone) on public.profiles to authenticated;
grant update (name, cnpj_cpf, primary_color, secondary_color) on public.companies to authenticated;

-- Records consent with the server clock (LGPD), e.g. when the terms version changes.
create or replace function public.accept_terms(terms_version text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (select auth.uid()) is null then
    raise exception 'not authenticated';
  end if;
  if terms_version is null or char_length(trim(terms_version)) not between 1 and 32 then
    raise exception 'invalid terms version';
  end if;
  update public.profiles
     set accepted_terms_at = now(), accepted_terms_version = trim(terms_version)
   where id = (select auth.uid());
end;
$$;

-- LGPD / Google Play: users can delete their own account; cascades remove their data.
create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (select auth.uid()) is null then
    raise exception 'not authenticated';
  end if;
  delete from auth.users where id = (select auth.uid());
end;
$$;

revoke all on function public.accept_terms(text) from public, anon;
revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.accept_terms(text) to authenticated;
grant execute on function public.delete_my_account() to authenticated;
