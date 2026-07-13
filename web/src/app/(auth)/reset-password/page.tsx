"use client";

// Landing page for the password-recovery email link.
//
// Two ways in:
//  - token_hash link (the email template's `?token_hash={{ .TokenHash }}`):
//    nothing is consumed on page load — we call verifyOtp only when the form
//    is submitted, so inbox link-scanners can GET the URL all day without
//    burning the one-time token.
//  - legacy session flow: /auth/callback already exchanged a recovery code
//    for a session; we just update the password.

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { createClient } from "@/lib/supabase-browser";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

function ResetPasswordForm() {
  const router = useRouter();
  const params = useSearchParams();
  const supabase = createClient();
  const tokenHash = params.get("token_hash");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [hasSession, setHasSession] = useState<boolean | null>(null);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => setHasSession(!!data.session));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const canReset = hasSession || !!tokenHash;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (password.length < 6) return setMsg("Use at least 6 characters.");
    if (password !== confirm) return setMsg("The two passwords don't match.");
    setLoading(true);
    setMsg(null);
    // Only now does the one-time token get spent.
    if (!hasSession && tokenHash) {
      const { error } = await supabase.auth.verifyOtp({ type: "recovery", token_hash: tokenHash });
      if (error) {
        setLoading(false);
        return setMsg("This reset link has expired or was already used, request a fresh one from the login page.");
      }
    }
    const { error } = await supabase.auth.updateUser({ password });
    setLoading(false);
    if (error) return setMsg(error.message);
    router.push("/dashboard");
    router.refresh();
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">Set a new password</CardTitle>
          <CardDescription>Pick a new password for your account.</CardDescription>
        </CardHeader>
        <CardContent>
          {hasSession === false && !canReset ? (
            <p className="text-sm text-muted-foreground">
              This page only works right after opening the reset link from your email. Go back to{" "}
              <a href="/login" className="underline">the login page</a> and tap “Forgot password?” to get a fresh link.
            </p>
          ) : (
            <form onSubmit={submit} className="space-y-3">
              <Input
                type="password"
                placeholder="New password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
              <Input
                type="password"
                placeholder="Repeat new password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                required
              />
              <Button type="submit" className="w-full" disabled={loading || hasSession == null}>
                {loading ? "Saving…" : "Save new password"}
              </Button>
              {msg && <p className="text-center text-xs text-amber-400">{msg}</p>}
            </form>
          )}
        </CardContent>
      </Card>
    </main>
  );
}

export default function ResetPasswordPage() {
  // useSearchParams requires a Suspense boundary in the app router.
  return (
    <Suspense>
      <ResetPasswordForm />
    </Suspense>
  );
}
