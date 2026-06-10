-- ============================================================================
-- Scheduled jobs. Runs sync-intervals for every user every 30 minutes.
--
-- Requires pg_cron + pg_net (both available on Supabase free tier).
-- The function is invoked over HTTP with the service_role key so it bypasses
-- RLS and can iterate all users. Set the two settings below once after deploy:
--
--   alter database postgres set "app.functions_base_url" = 'https://<ref>.functions.supabase.co';
--   alter database postgres set "app.service_role_key"   = '<service_role_key>';
--
-- (Storing the key as a DB setting keeps it out of the SQL body / logs.)
-- ============================================================================

create extension if not exists pg_cron  with schema extensions;
create extension if not exists pg_net   with schema extensions;

create or replace function trigger_sync_intervals()
returns void
language plpgsql
security definer
as $$
declare
  base_url text := current_setting('app.functions_base_url', true);
  srk      text := current_setting('app.service_role_key', true);
begin
  if base_url is null or srk is null then
    raise notice 'sync-intervals cron skipped: app.functions_base_url / app.service_role_key not set';
    return;
  end if;

  perform net.http_post(
    url     := base_url || '/sync-intervals',
    headers := jsonb_build_object(
      'Content-Type',  'application/json',
      'Authorization', 'Bearer ' || srk
    ),
    body    := jsonb_build_object('source', 'cron', 'all_users', true)
  );
end;
$$;

select cron.schedule(
  'sync-intervals-every-30m',
  '*/30 * * * *',
  $$ select trigger_sync_intervals(); $$
);
