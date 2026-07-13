# Workout Maker — Privacy Policy

_Last updated: 2026-07-13_

Workout Maker is an open-source training app (AGPL-3.0). This policy covers the
Android app and web companion when used with the official hosted backend. If you
self-host the backend, your deployment's operator (you) controls all data and this
policy does not apply.

**Contact:** mitsis.spiros1@gmail.com

## What we store

All data lives in the app's Supabase (PostgreSQL) backend, scoped to your account
and protected by row-level security — no other user can read your rows.

- **Account**: your email address and a password hash (handled by Supabase Auth).
- **Training data you create or sync**: planned and completed workouts, strength
  logs, wellness check-ins, races/goals, coach conversations, and workout feedback.
- **Health data (optional)**: if you connect Health Connect, the app reads heart
  rate, resting heart rate, HRV, sleep, steps, and VO₂max **with your explicit
  permission** and stores daily summaries to compute readiness and personalize
  coaching. This data is never used for advertising and never sold or shared —
  see "Third parties" below for the only flows that exist.
- **API keys you provide**: your LLM provider key and Intervals.icu key are
  encrypted at rest (pgcrypto) with a key held only as a server-side secret, and
  are used exclusively server-side. They are never sent to any client.
- **Generation logs**: each AI request's model, token counts, and estimated cost,
  so you can audit your own spend.
- **Crash diagnostics**: if the app crashes, a technical report (app version,
  Android version, device model, and the error's stack trace) is stored in your
  account's rows so the developer can fix the bug. No precise location, no
  advertising identifiers, no third-party crash SDK; the report never leaves the
  app's own backend and is deleted with your account.
- **Approximate location (optional)**: coarse location is used on-device to fetch
  weather for outdoor session planning. It is not stored on the server.

## Third parties

Data leaves the backend only in these flows, each of which you configure yourself:

- **Your chosen LLM provider** (e.g. Anthropic, OpenAI, Google, Groq, DeepSeek, or
  a custom endpoint): receives the training context needed to generate workouts
  and coaching replies, under that provider's privacy terms and your own API key.
- **Intervals.icu** (optional): planned workouts are pushed to, and completed
  activities pulled from, your Intervals.icu account using your API key.
- **Hosted AI (Pro subscribers)**: if you subscribe to Pro and leave hosted AI
  on, coaching and workout generation run on an LLM provider chosen and paid for
  by the operator (currently DeepSeek) instead of your own key. The same
  training context is sent as in the bring-your-own-key flow, under that
  provider's privacy terms. You can switch back to your own keys at any time in
  Settings.

There are **no ads, no analytics SDKs, no trackers**, and no sale or sharing of
personal data with anyone.

## Data retention & deletion

Your data is kept until you delete it. **Settings → Account → "Delete account &
all data"** permanently removes your account and every row you own (profile,
workouts, logs, conversations, encrypted keys) with no undo. You can also request
deletion by emailing the contact address above; requests are honored within 30
days. Data previously pushed to Intervals.icu or sent to your LLM provider is
governed by those services and must be deleted there.

## Security

- Row-level security on every table; users can only access their own data.
- API keys encrypted at rest; decryption is possible only inside server-side edge
  functions.
- All transport is HTTPS.

## Children

Workout Maker is not directed at children under 16 and does not knowingly collect
their data.

## Changes

Material changes to this policy will be noted in the project changelog and this
file's history (the repository is public).
