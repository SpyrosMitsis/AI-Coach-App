-- account_exists(email): lets the sign-in screen tell "no account yet" apart
-- from "wrong password" (Supabase returns the same error for both). SECURITY
-- DEFINER so it can read auth.users; returns only a boolean. Granted to anon
-- (the check runs pre-auth) by design for this personal / self-hostable app.
create or replace function public.account_exists(p_email text)
returns boolean
language sql
security definer
set search_path = public, auth
as $$
  select exists (
    select 1 from auth.users where lower(email) = lower(trim(p_email))
  );
$$;

grant execute on function public.account_exists(text) to anon, authenticated;
