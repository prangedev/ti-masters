-- Applied to project "ti-masters" (snbmafhkirlxyrnyzxrt) on 2026-10-19.

-- Subscriptions are written only by server code (Edge Functions with the service role, after
-- validating Google Play purchases). The app can read its own rows and nothing else.
create table public.subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  company_id uuid references public.companies (id) on delete set null,
  plan text not null check (plan in ('basic', 'advanced', 'business')),
  status text not null check (status in ('active', 'grace', 'on_hold', 'paused', 'canceled', 'expired')),
  product_id text,
  purchase_token_hash text unique,
  expires_at timestamptz,
  auto_renewing boolean not null default false,
  last_verified_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index subscriptions_user_id_idx on public.subscriptions (user_id);
create index subscriptions_company_id_idx on public.subscriptions (company_id);

create trigger subscriptions_updated_at before update on public.subscriptions
  for each row execute function private.set_updated_at();

alter table public.subscriptions enable row level security;

create policy "Users read own subscriptions" on public.subscriptions
  for select to authenticated using ((select auth.uid()) = user_id);

revoke all on public.subscriptions from anon;
revoke insert, update, delete on public.subscriptions from authenticated;

-- Plan order used for "at least this plan" checks.
create or replace function private.plan_rank(plan text)
returns integer
language sql
immutable
set search_path = ''
as $$
  select case plan when 'business' then 3 when 'advanced' then 2 else 1 end;
$$;

-- A subscription grants access while active or in Google Play's grace period and not expired.
create or replace function private.is_entitling(status text, expires_at timestamptz)
returns boolean
language sql
stable
set search_path = ''
as $$
  select status in ('active', 'grace') and (expires_at is null or expires_at > now());
$$;

-- What the app asks on start: the user's best current plan plus the server clock.
create or replace function public.get_my_entitlement()
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select jsonb_build_object(
    'plan', coalesce(best.plan, 'basic'),
    'expires_at', best.expires_at,
    'server_time', now()
  )
  from (select 1) as one
  left join lateral (
    select s.plan, s.expires_at
      from public.subscriptions s
     where s.user_id = (select auth.uid())
       and private.is_entitling(s.status, s.expires_at)
     order by private.plan_rank(s.plan) desc, s.expires_at desc nulls first
     limit 1
  ) as best on true;
$$;

-- For RLS on future Business tables: does this company have (at least) the given plan?
-- A company's plan comes from subscriptions attached to it (bought by its owner).
create or replace function private.company_has_plan(target_company_id uuid, min_plan text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
      from public.subscriptions s
      join public.companies c on c.id = s.company_id
     where s.company_id = target_company_id
       and s.user_id = c.owner_id
       and private.is_entitling(s.status, s.expires_at)
       and private.plan_rank(s.plan) >= private.plan_rank(min_plan)
  );
$$;

revoke all on function private.plan_rank(text) from public;
revoke all on function private.is_entitling(text, timestamptz) from public;
revoke all on function private.company_has_plan(uuid, text) from public;
grant execute on function private.plan_rank(text) to authenticated;
grant execute on function private.is_entitling(text, timestamptz) to authenticated;
grant execute on function private.company_has_plan(uuid, text) to authenticated;

revoke all on function public.get_my_entitlement() from public, anon;
grant execute on function public.get_my_entitlement() to authenticated;
