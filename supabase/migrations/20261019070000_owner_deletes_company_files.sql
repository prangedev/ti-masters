-- Account deletion (LGPD): Postgres cannot delete Storage files, so the app removes the files of
-- the companies the user owns right before calling delete_my_account(). Owners may delete their
-- company files even without an active Business plan (e.g. after the subscription ended).

drop policy "Business members delete company files" on storage.objects;

create policy "Business members or owners delete company files" on storage.objects
  for delete to authenticated
  using (
    bucket_id in ('logos', 'service-orders')
    and private.is_company_member(private.folder_company(name))
    and (
      private.company_has_plan(private.folder_company(name), 'business')
      or private.has_company_role(private.folder_company(name), array['owner'])
    )
  );

-- Companies the signed-in user owns (deleted together with the account).
create or replace function public.get_my_owned_companies()
returns setof uuid
language sql
stable
security invoker
set search_path = ''
as $$
  select m.company_id from public.company_members m
   where m.user_id = (select auth.uid()) and m.role = 'owner';
$$;

revoke all on function public.get_my_owned_companies() from public, anon;
grant execute on function public.get_my_owned_companies() to authenticated;
