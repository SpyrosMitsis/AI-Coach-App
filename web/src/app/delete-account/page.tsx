// Public account-deletion page — linked from the Google Play data-safety form,
// which requires a web-reachable deletion path that works without the app.
export const metadata = { title: "Delete your account. Workout Maker" };

export default function DeleteAccountPage() {
  return (
    <main className="mx-auto max-w-xl px-6 py-16 space-y-6">
      <h1 className="text-2xl font-semibold">Delete your Workout Maker account</h1>
      <p className="text-muted-foreground">
        Deleting your account permanently removes everything you own, profile,
        workouts, strength logs, wellness data, coach conversations, and your
        encrypted API keys. There is no undo.
      </p>
      <section className="space-y-2">
        <h2 className="text-lg font-medium">In the app (instant)</h2>
        <p className="text-muted-foreground">
          Open <strong>Settings → Account → “Delete account &amp; all data”</strong> and
          confirm. Deletion is immediate.
        </p>
      </section>
      <section className="space-y-2">
        <h2 className="text-lg font-medium">By email</h2>
        <p className="text-muted-foreground">
          Can’t access the app? Email{" "}
          <a className="underline" href="mailto:mitsis.spiros1@gmail.com?subject=Delete%20my%20Workout%20Maker%20account">
            mitsis.spiros1@gmail.com
          </a>{" "}
          from your account’s email address with the subject “Delete my Workout Maker
          account”. Requests are honored within 30 days.
        </p>
      </section>
      <p className="text-sm text-muted-foreground">
        Data previously pushed to Intervals.icu or sent to your chosen LLM provider is
        governed by those services and must be deleted there. See the{" "}
        <a
          className="underline"
          href="https://github.com/SpyrosMitsis/AI-Coach-App/blob/main/docs/PRIVACY.md"
        >
          privacy policy
        </a>
        .
      </p>
    </main>
  );
}
