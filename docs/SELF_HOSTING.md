# Self-hosting Workout Maker

Workout Maker is AGPL-3.0 free software. You can run the entire stack yourself on
Supabase's free tier — you bring your own LLM and Intervals.icu keys, so there are no
platform costs. This guide takes you from a fresh clone to a working app pointed at
your own backend.

## What you'll run

| Piece | Where it runs | Cost |
|-------|---------------|------|
| Postgres + Auth + Edge Functions | Your Supabase project | Free tier is enough for personal use |
| Web companion (optional) | Vercel (or any Next.js host) | Free tier |
| Android app | Your phone (sideloaded APK or your own build) | — |

## 1. Create the Supabase project

1. Sign up at [supabase.com](https://supabase.com) and create a project. Note the
   **project ref** (the `xxxx` in `https://xxxx.supabase.co`), the **anon key**, and the
   **service-role key** (Settings → API).
2. Install the [Supabase CLI](https://supabase.com/docs/guides/cli) and log in:

```bash
supabase login
supabase link --project-ref YOUR-PROJECT-REF
```

## 2. Database schema

```bash
supabase db push        # applies everything in supabase/migrations
```

## 3. Secrets

The only secret the edge functions need is the symmetric key used to encrypt your
LLM / Intervals.icu API keys at rest:

```bash
openssl rand -base64 48 | xargs -I{} supabase secrets set ENCRYPTION_KEY="{}"
```

Treat it like a password: it decrypts every stored API key. Losing it just means
re-entering your keys in the app; leaking it means rotating it (see below).

## 4. Deploy the edge functions

```bash
supabase functions deploy      # deploys all functions in supabase/functions
```

## 5. Enable the cron jobs

The 30-minute Intervals.icu sync and weekly auto-planner run via `pg_cron`, which calls
back into your edge functions. Tell the database where they live (one-time, in the
Supabase SQL editor):

```sql
alter database postgres set "app.functions_base_url" = 'https://YOUR-PROJECT-REF.functions.supabase.co';
alter database postgres set "app.service_role_key"   = 'YOUR-SERVICE-ROLE-KEY';
```

## 6. Auth settings (Supabase dashboard)

For a personal instance the defaults are fine. If anyone besides you can reach the
signup screen, in **Authentication → Settings** enable **email confirmations** and
review the rate limits. (`supabase/config.toml` only affects local development.)

## 7. Point the Android app at your backend

Build it yourself with your project's values (anon key is client-safe):

```bash
cd android
cat >> local.properties <<EOF
SUPABASE_URL=https://YOUR-PROJECT-REF.supabase.co
SUPABASE_ANON_KEY=YOUR-ANON-KEY
EOF
./gradlew assembleRelease   # needs JDK 17
adb install app/build/outputs/apk/release/app-release.apk
```

Or skip the build entirely: the released APK has an **"Advanced: custom server"**
option on the sign-in screen — enter your Supabase URL + anon key there and the
app restarts pointed at your backend.

## 8. Web companion (optional)

```bash
cd web
cp .env.example .env.local    # NEXT_PUBLIC_SUPABASE_URL + NEXT_PUBLIC_SUPABASE_ANON_KEY
npm install && npm run dev    # or `vercel --prod` to deploy
```

## 9. First run

Sign up in the app, then during onboarding (or in Settings):

- **LLM key** — free-tier options: [Groq](https://console.groq.com/keys),
  [Gemini](https://aistudio.google.com/app/apikey). Any OpenAI-compatible endpoint works
  via the Custom provider (including local Ollama).
- **Intervals.icu** — Athlete ID + API key from intervals.icu → Settings → Developer.

Generate a workout to confirm the whole path works.

## Hosted AI ("Pro") and self-hosting

The Play Store build offers an optional paid tier where the maintainer's server-side
LLM key powers generation. That capability is advertised by the backend itself — a
self-hosted instance without the hosted-AI secrets simply never shows any Pro UI.
Nothing in the app phones home to the maintainer's infrastructure; your instance is
fully independent, and every feature works on the free bring-your-own-key model.

## Rotating `ENCRYPTION_KEY`

If the key may have leaked you have two options.

**Option A — cheap (few users, keys are re-enterable):** set a new key, delete the
stored ciphertexts, re-enter API keys in the app:

```bash
openssl rand -base64 48 | xargs -I{} supabase secrets set ENCRYPTION_KEY="{}"
```

```sql
delete from llm_api_keys;
update user_profiles set intervals_api_key_encrypted = null;
```

**Option B — in-place re-encrypt (no user action needed):** run before* changing the
secret, using the crypto RPCs from migration `20260607000005_crypto_rpc.sql` (SQL
editor, as service role; `:old_key` / `:new_key` are the base64 secrets):

```sql
update llm_api_keys
   set api_key_encrypted = encrypt_for_app(:new_key, decrypt_for_app(:old_key, api_key_encrypted));

update user_profiles
   set intervals_api_key_encrypted = encrypt_for_app(:new_key, decrypt_for_app(:old_key, intervals_api_key_encrypted))
 where intervals_api_key_encrypted is not null;
```

Then `supabase secrets set ENCRYPTION_KEY="<new_key>"`. \*Functions restart on
`secrets set`, so re-encrypt first and flip the secret immediately after.

## Troubleshooting

- `fn:logs` / function errors: `scripts/dev.sh fn:logs <name>` (set your project ref in
  `scripts/dev.local.sh` first — see `dev.sh` header). `WM_LOG=debug` for verbose JSON logs.
- Local stack: `supabase start` + `scripts/dev.sh fn:serve`; seeded login
  `athlete@example.com / password123` (fictional data from `supabase/seed.sql`).
- Shared-module tests: `scripts/dev.sh deno:test`.
