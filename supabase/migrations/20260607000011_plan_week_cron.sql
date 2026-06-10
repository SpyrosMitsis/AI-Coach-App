-- Weekly auto-advance: every Sunday evening, plan the upcoming week for users
-- who opted in (user_profiles.auto_plan = true). Reuses the same service-role
-- HTTP pattern as the sync-intervals cron (app.functions_base_url /
-- app.service_role_key must be set — see 20260607000004_cron.sql).

create or replace function trigger_plan_week()
returns void
language plpgsql
security definer
as $$
declare
  base_url text := current_setting('app.functions_base_url', true);
  srk      text := current_setting('app.service_role_key', true);
begin
  if base_url is null or srk is null then
    raise notice 'plan-week cron skipped: app.functions_base_url / app.service_role_key not set';
    return;
  end if;

  perform net.http_post(
    url     := base_url || '/plan-week',
    headers := jsonb_build_object(
      'Content-Type',  'application/json',
      'Authorization', 'Bearer ' || srk
    ),
    body    := jsonb_build_object('source', 'cron', 'all_users', true, 'push', true)
  );
end;
$$;

select cron.schedule(
  'plan-week-weekly',
  '0 18 * * 0',   -- Sundays 18:00 UTC → plans the upcoming Mon-Sun
  $$ select trigger_plan_week(); $$
);
