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
- Authentication → rate limits: tighten sign-ups/OTPs per hour.
- Set a **hard spend cap on your LLM provider account** — the real kill switch.
- Usage alerts on (email when nearing free-tier limits).

## Each release

```bash
# bump versionCode (and versionName) in android/app/build.gradle.kts first
cd android && ./gradlew :app:bundleRelease        # needs JDK 17
# upload android/app/build/outputs/bundle/release/app-release.aab
```

Smoke-test the minified build before uploading (serialization is the classic
R8 casualty): sign-in, daily summary, generate workout, coach chat (SSE
stream), strength logging, Health Connect sync.

## Track progression

1. **Internal testing** — you + a couple of devices, instant availability.
2. **Closed testing** — the 12-tester/14-day clock runs here; collect the
   pre-launch report (it exercises the app on real devices).
3. **Production** — staged rollout (start at 20%), watch Play vitals (crashes /
   ANRs) and `scripts/ops-report.sh`.
