// Public privacy policy — linked from the Google Play listing, the Health
// Connect declaration, and the in-app/legal surfaces. Mirrors docs/PRIVACY.md
// (keep the two in sync when either changes).
export const metadata = { title: "Privacy policy. Workout Maker" };

export default function PrivacyPage() {
  return (
    <main className="mx-auto max-w-xl px-6 py-16 space-y-6">
      <h1 className="text-2xl font-semibold">Workout Maker privacy policy</h1>
      <p className="text-sm text-muted-foreground">Last updated: 2026-07-13</p>
      <p className="text-muted-foreground">
        Workout Maker is an open-source training app (AGPL-3.0). This policy covers
        the Android app and web companion when used with the official hosted
        backend. If you self-host the backend, your deployment&apos;s operator (you)
        controls all data and this policy does not apply.
      </p>
      <p className="text-muted-foreground">
        Contact:{" "}
        <a className="underline" href="mailto:mitsis.spiros1@gmail.com">
          mitsis.spiros1@gmail.com
        </a>
      </p>

      <section className="space-y-2">
        <h2 className="text-lg font-medium">What we store</h2>
        <p className="text-muted-foreground">
          All data lives in the app&apos;s Supabase (PostgreSQL) backend, scoped to
          your account and protected by row-level security. No other user can read
          your rows.
        </p>
        <ul className="list-disc space-y-2 pl-5 text-muted-foreground">
          <li>
            <strong>Account</strong>: your email address and a password hash
            (handled by Supabase Auth).
          </li>
          <li>
            <strong>Training data you create or sync</strong>: planned and
            completed workouts, strength logs, wellness check-ins, races and
            goals, coach conversations, and workout feedback.
          </li>
          <li>
            <strong>Health data (optional)</strong>: if you connect Health
            Connect, the app reads heart rate, resting heart rate, HRV, sleep,
            steps, and VO₂max with your explicit permission and stores daily
            summaries to compute readiness and personalize coaching. This data is
            never used for advertising and never sold or shared. See &quot;Third
            parties&quot; below for the only flows that exist.
          </li>
          <li>
            <strong>API keys you provide</strong>: your LLM provider key and
            Intervals.icu key are encrypted at rest (pgcrypto) with a key held
            only as a server-side secret, and are used exclusively server-side.
            They are never sent to any client.
          </li>
          <li>
            <strong>Generation logs</strong>: each AI request&apos;s model, token
            counts, and estimated cost, so you can audit your own spend.
          </li>
          <li>
            <strong>Crash diagnostics</strong>: if the app crashes, a technical
            report (app version, Android version, device model, and the
            error&apos;s stack trace) is stored in your account&apos;s rows so the
            developer can fix the bug. No precise location, no advertising
            identifiers, no third-party crash SDK; the report never leaves the
            app&apos;s own backend and is deleted with your account.
          </li>
          <li>
            <strong>Approximate location (optional)</strong>: coarse location is
            used on-device to fetch weather for outdoor session planning. It is
            not stored on the server.
          </li>
        </ul>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-medium">Third parties</h2>
        <p className="text-muted-foreground">
          Data leaves the backend only in these flows, each of which you configure
          yourself:
        </p>
        <ul className="list-disc space-y-2 pl-5 text-muted-foreground">
          <li>
            <strong>Your chosen LLM provider</strong> (e.g. Anthropic, OpenAI,
            Google, Groq, DeepSeek, or a custom endpoint): receives the training
            context needed to generate workouts and coaching replies, under that
            provider&apos;s privacy terms and your own API key.
          </li>
          <li>
            <strong>Intervals.icu (optional)</strong>: planned workouts are pushed
            to, and completed activities pulled from, your Intervals.icu account
            using your API key.
          </li>
          <li>
            <strong>Hosted AI (Pro subscribers)</strong>: if you subscribe to Pro
            and leave hosted AI on, coaching and workout generation run on an LLM
            provider chosen and paid for by the operator (currently DeepSeek)
            instead of your own key. The same training context is sent as in the
            bring-your-own-key flow, under that provider&apos;s privacy terms. You
            can switch back to your own keys at any time in Settings.
          </li>
        </ul>
        <p className="text-muted-foreground">
          There are no ads, no analytics SDKs, no trackers, and no sale or sharing
          of personal data with anyone.
        </p>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-medium">Data retention &amp; deletion</h2>
        <p className="text-muted-foreground">
          Your data is kept until you delete it. In the app, <strong>Settings →
          Account → &quot;Delete account &amp; all data&quot;</strong> permanently
          removes your account and every row you own (profile, workouts, logs,
          conversations, encrypted keys, crash reports) with no undo. You can also
          use the <a className="underline" href="/delete-account">web deletion
          page</a> or email the contact address above; requests are honored within
          30 days. Data previously pushed to Intervals.icu or sent to your LLM
          provider is governed by those services and must be deleted there.
        </p>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-medium">Security</h2>
        <ul className="list-disc space-y-2 pl-5 text-muted-foreground">
          <li>Row-level security on every table; users can only access their own data.</li>
          <li>
            API keys encrypted at rest; decryption is possible only inside
            server-side edge functions.
          </li>
          <li>All transport is HTTPS.</li>
        </ul>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-medium">Children</h2>
        <p className="text-muted-foreground">
          Workout Maker is not directed at children under 16 and does not knowingly
          collect their data.
        </p>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-medium">Changes</h2>
        <p className="text-muted-foreground">
          Material changes to this policy will be noted in the project changelog
          and the{" "}
          <a
            className="underline"
            href="https://github.com/SpyrosMitsis/AI-Coach-App/blob/main/docs/PRIVACY.md"
          >
            policy file&apos;s history
          </a>{" "}
          (the repository is public).
        </p>
      </section>
    </main>
  );
}
