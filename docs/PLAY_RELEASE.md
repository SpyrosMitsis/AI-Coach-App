# Google Play release runbook

The code side (signing, minify, account deletion, privacy policy, debug-only
cleartext config) is in the repo. This file is the **console-side** checklist —
none of it can be automated from here.

## One-time setup

1. **Play developer account** (~$25). New personal accounts must run a closed test
   with **12 testers opted-in for 14 continuous days** before production access —
   recruit testers first, this gates the whole timeline.
2. **Keystore**: the upload keystore lives at
   `~/.android-keystores/workoutmaker-upload.jks` with credentials in
   `android/local.properties` (`RELEASE_*`). **Back both up now** (password
   manager + offline copy). Enroll in **Play App Signing** at first upload —
   Google then holds the app signing key and the upload key is replaceable.
3. Create the app: package `com.workoutmaker.app`, free, no ads.

## Declarations (start early — these have review lead time)

- **Privacy policy URL** (required, gates everything): publish `docs/PRIVACY.md`
  publicly and paste its URL. The public GitHub blob URL works, or the web app's
  domain if preferred.
- **Health apps declaration** (required because of Health Connect READ
  permissions; review can take **weeks** — file with the first closed-testing
  build): purpose = fitness & wellness coaching; data read: heart rate, resting
  HR, HRV, sleep, steps, VO₂max; used for readiness scoring and workout
  personalization; not used for ads.
- **Data safety form** (must mirror the privacy policy):
  - Collected: email (account), health & fitness data (app functionality),
    approximate location (optional, app functionality, not stored server-side).
  - Shared: training context → user-configured LLM provider; workouts ↔
    Intervals.icu (both user-initiated, user-supplied keys).
  - All data encrypted in transit; deletion path = in-app + web URL
    (`https://<web-app-domain>/delete-account`).
  - No ads, no analytics, no data sold.
- **SCHEDULE_EXACT_ALARM declaration**: user-initiated rest timer (accepted use
  case; the app already falls back to inexact alarms when not granted).
- **Foreground service (health) declaration**: keeps the live workout session
  timer running; triggered only by an explicit user action.

## Supabase production settings (dashboard, not config.toml)

- Authentication → enable **email confirmations**.
- Authentication → URL Configuration → **Site URL = the deployed web app**
  (e.g. `https://<app>.vercel.app`) — confirmation and password-reset emails
  redirect there; the default is `localhost:3000`, which lands users on a dead
  page. Add `…/auth/callback` and `…/reset-password` to additional redirect URLs.
- Authentication → rate limits: tighten sign-ups/OTPs per hour.
- Set a **hard spend cap on your LLM provider account** — the real kill switch.
- Usage alerts on (email when nearing free-tier limits).

## Each release

```bash
# bump versionCode (and versionName) in android/app/build.gradle.kts first
cd android && ./gradlew :app:bundlePlayRelease    # needs JDK 17
# upload android/app/build/outputs/bundle/playRelease/app-play-release.aab
```

(The `foss` flavor is for F-Droid/self-hosters — no Google Billing code; never
upload it to Play.)

Smoke-test the minified build before uploading (serialization is the classic
R8 casualty): sign-in, daily summary, generate workout, coach chat (SSE
stream), strength logging, Health Connect sync.

## Pro subscription (billing + hosted AI) — one-time console setup

Code side is in the repo (`verify-purchase` / `play-rtdn` edge fns, migration 35,
`llmAccess()` hosted path, Settings Pro UI). Console side:

1. **Subscription product**: Play Console → Monetize → Subscriptions → create
   product id **`pro`** (must match `PRO_PRODUCT_ID` in
   `billing/BillingGateway.kt`) with one base plan (e.g. monthly).
2. **Service account**: GCP project → enable *Google Play Android Developer
   API* → create a service account + JSON key → in Play Console → Users &
   permissions, grant it *View financial data* + *Manage orders*. Then
   `supabase secrets set GOOGLE_PLAY_SA_JSON='<the JSON, one line>'`.
3. **RTDN**: GCP Pub/Sub → create topic `play-rtdn`; Play Console → Monetize →
   Monetization setup → set the topic. Add a **push subscription** pointing at
   `https://<ref>.supabase.co/functions/v1/play-rtdn?secret=<PLAY_RTDN_SECRET>`
   (set the same value with `supabase secrets set PLAY_RTDN_SECRET=…`).
   Deploy that fn with `--no-verify-jwt`. Send a test notification from the
   Play Console and check `fn:logs play-rtdn`.
4. **Hosted AI secrets**: `HOSTED_LLM_PROVIDER`, `HOSTED_LLM_KEY`, optional
   `HOSTED_LLM_MODEL`, and the USD caps (`HOSTED_USER_DAILY_USD` /
   `HOSTED_USER_MONTHLY_USD` / `HOSTED_GLOBAL_MONTHLY_USD`). Kill switch:
   `HOSTED_AI_DISABLED=1`. Without these, no Pro UI ever shows in the app.
5. **License testers** (Play Console → Settings → License testing): test
   purchases with accelerated renewals; exercise purchase → cancel →
   resubscribe and watch `billing_events`.

### Cost safety (the "can't go in the red" rails)

The quota gate lives in `_shared/quota.ts` and fails closed. Layers, inner to
outer:

- **Per-call ceiling**: every adapter caps output at 2,500 tokens (invariant
  documented in `_shared/llm.ts`). One hosted call on a flash/mini-class model
  costs ~$0.01; a worst-case agentic chat turn (~12 calls) ~$0.12.
- **Model class**: `HOSTED_LLM_MODEL` must stay flash/mini-class, e.g.
  `gemini-2.5-flash` or `gpt-5-mini`. Never an opus-class model: one agentic
  turn would cost ~$2 and the caps assume cents. (`entitlement.ts` warn-logs
  if the model id looks expensive.)
- **Quota env** (suggested for ~€5/mo Pro, ~$4.60 net after Play's 15%):
  `HOSTED_USER_HOURLY_CALLS=30`, `HOSTED_USER_DAILY_USD=0.20`,
  `HOSTED_USER_MONTHLY_USD=2` (the hard per-user loss bound),
  `HOSTED_GLOBAL_MONTHLY_USD` ≈ `10 + 2.5 × paying subscribers` (start 25,
  revisit at each ~10-subscriber milestone). Note: caps are checked before a
  turn, so a turn in flight can overshoot by ~$0.12; that's priced in.
- **Kill switch**: `supabase secrets set HOSTED_AI_DISABLED=1` — takes effect
  on the next invocation, no redeploy. Pro users fall back to their own keys
  (or the standard "no key" message); the app hides Pro UI automatically via
  `server.hosted_ai=false`.
- **The true backstop**: a **hard billing cap on the LLM provider account** at
  ~2× the global cap (e.g. $50 while global is $25). Set it before the first
  hosted user exists.
- **Free-tier surface** (BYO keys cost you nothing; shared cost is Supabase):
  keep the Supabase spend cap ON so overuse degrades instead of billing, set
  usage alerts at ~75% of edge-invocation/DB quotas, tighten Auth rate limits
  (sign-ups, OTPs, resets) in the dashboard. Turnstile captcha is a documented
  later option if signup abuse ever shows up.
- **Watchdogs**: `scripts/ops-report.sh` now shows hosted spend by day, hosted
  top spenders vs caps, and recent `billing_events`. In Play Console, enable
  monitoring alerts for RTDN delivery failures.

## Track progression

1. **Internal testing** — you + a couple of devices, instant availability.
2. **Closed testing** — the 12-tester/14-day clock runs here; collect the
   pre-launch report (it exercises the app on real devices).
3. **Production** — staged rollout (start at 20%), watch Play vitals (crashes /
   ANRs) and `scripts/ops-report.sh`.
