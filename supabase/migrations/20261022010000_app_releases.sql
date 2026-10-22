-- In-app updates (test channel, outside the Play Store).
-- The GitHub Actions release workflow builds the APK and publishes it through the
-- "publish-release" Edge Function, which writes this table and the "app-releases" bucket
-- with the service role. Signed-in app users can only read.

create table public.app_releases (
  version_code integer primary key check (version_code > 0),
  version_name text not null check (length(version_name) between 1 and 32),
  notes text not null default '' check (length(notes) <= 4000),
  apk_path text not null,
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  size_bytes bigint not null check (size_bytes > 0),
  -- Installed versions below this code must update (no "Depois" button).
  min_supported_code integer not null default 0 check (min_supported_code >= 0),
  published_at timestamptz not null default now()
);

alter table public.app_releases enable row level security;

create policy "Signed-in users read app releases"
  on public.app_releases for select
  to authenticated
  using (true);

revoke all on public.app_releases from anon, authenticated;
grant select on public.app_releases to authenticated;

-- 50 MB is the per-file ceiling of the free plan.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('app-releases', 'app-releases', false, 52428800, array['application/vnd.android.package-archive']);

create policy "Signed-in users download app releases"
  on storage.objects for select
  to authenticated
  using (bucket_id = 'app-releases');
