"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { createClient } from "@/lib/supabase-browser";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

// Supabase auth errors are terse and technical — translate the common ones.
function friendlyAuthError(message: string): string {
  const m = message.toLowerCase();
  if (m.includes("invalid login credentials")) return "Wrong email or password.";
  if (m.includes("email not confirmed")) {
    return "Your email isn't confirmed yet, check your inbox for the confirmation link.";
  }
  if (m.includes("already registered")) {
    return "An account with this email already exists, sign in instead.";
  }
  if (m.includes("password should be")) return "Password is too short, use at least 6 characters.";
  if (m.includes("rate limit") || m.includes("too many")) {
    return "Too many attempts, wait a minute and try again.";
  }
  if (m.includes("is invalid") || m.includes("validate email")) {
    return "That doesn't look like a valid email address.";
  }
  if (m.includes("fetch") || m.includes("network")) {
    return "Can't reach the server, check your connection and try again.";
  }
  return message;
}

export default function LoginPage() {
  const router = useRouter();
  const supabase = createClient();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [mode, setMode] = useState<"password" | "magic">("password");
  const [msg, setMsg] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function signIn(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setMsg(null);
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    setLoading(false);
    if (error) return setMsg(friendlyAuthError(error.message));
    router.push("/dashboard");
    router.refresh();
  }

  async function signUp() {
    setLoading(true);
    setMsg(null);
    const { data, error } = await supabase.auth.signUp({ email, password });
    setLoading(false);
    if (error) return setMsg(friendlyAuthError(error.message));
    // With email confirmation enabled there is no session yet — tell the user
    // what to do instead of silently bouncing them to onboarding.
    if (!data.session) {
      return setMsg("Almost there, check your inbox and confirm your email, then sign in.");
    }
    router.push("/onboarding");
    router.refresh();
  }

  async function magicLink(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setMsg(null);
    const { error } = await supabase.auth.signInWithOtp({
      email,
      options: { emailRedirectTo: `${location.origin}/auth/callback` },
    });
    setLoading(false);
    setMsg(error ? friendlyAuthError(error.message) : "Check your email for the magic link.");
  }

  async function forgotPassword() {
    if (!email) return setMsg("Type your email above first, then tap “Forgot password?”.");
    setLoading(true);
    setMsg(null);
    const { error } = await supabase.auth.resetPasswordForEmail(email, {
      redirectTo: `${location.origin}/auth/callback?next=/reset-password`,
    });
    setLoading(false);
    setMsg(
      error
        ? friendlyAuthError(error.message)
        : "Check your email, we sent you a link to set a new password.",
    );
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">Workout Maker</CardTitle>
          <CardDescription>
            AI-personalized running & strength, synced to your Amazfit.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={mode === "password" ? signIn : magicLink} className="space-y-3">
            <Input
              type="email"
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
            {mode === "password" && (
              <Input
                type="password"
                placeholder="Password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            )}
            <Button type="submit" className="w-full" disabled={loading}>
              {mode === "password" ? "Sign in" : "Send magic link"}
            </Button>
            {mode === "password" && (
              <Button type="button" variant="outline" className="w-full" onClick={signUp} disabled={loading}>
                Create account
              </Button>
            )}
            {mode === "password" && (
              <button
                type="button"
                className="w-full text-center text-xs text-muted-foreground hover:text-foreground"
                onClick={forgotPassword}
                disabled={loading}
              >
                Forgot password?
              </button>
            )}
            <button
              type="button"
              className="w-full text-center text-xs text-muted-foreground hover:text-foreground"
              onClick={() => setMode(mode === "password" ? "magic" : "password")}
            >
              {mode === "password" ? "Use a magic link instead" : "Use email + password"}
            </button>
            {msg && <p className="text-center text-xs text-amber-400">{msg}</p>}
          </form>
        </CardContent>
      </Card>
    </main>
  );
}
