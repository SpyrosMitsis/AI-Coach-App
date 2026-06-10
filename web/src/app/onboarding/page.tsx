"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { createClient } from "@/lib/supabase-browser";
import { api } from "@/lib/api";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import {
  PROVIDER_FREE_KEY_URL, PROVIDER_LABELS, type LlmProvider,
} from "@shared/types";
import { CheckCircle2, ExternalLink } from "lucide-react";

const GOALS = ["5K pace", "10K pace", "Half Marathon", "Marathon pace", "General fitness", "Muscle gain", "Body recomposition", "Hybrid athlete"];
const EQUIPMENT = ["Bodyweight", "Dumbbells", "Full gym", "Barbell + rack"];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const DURATIONS = [30, 45, 60, 90];
const FREE_TIER = new Set<LlmProvider>(["groq", "gemini"]);

export default function OnboardingPage() {
  const router = useRouter();
  const supabase = createClient();
  const [step, setStep] = useState(1);

  // Step 1
  const [athleteId, setAthleteId] = useState("");
  const [intervalsKey, setIntervalsKey] = useState("");
  const [verifiedName, setVerifiedName] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // Step 2
  const [goal, setGoal] = useState("10K pace");
  const [experience, setExperience] = useState<"Beginner" | "Intermediate" | "Advanced">("Intermediate");
  const [days, setDays] = useState<string[]>(["Mon", "Wed", "Fri", "Sat"]);
  const [duration, setDuration] = useState(60);
  const [equipment, setEquipment] = useState("Full gym");
  const [raceResult, setRaceResult] = useState("");
  const [injury, setInjury] = useState("");

  // Step 3
  const [provider, setProvider] = useState<LlmProvider>("groq");
  const [llmKey, setLlmKey] = useState("");

  async function verifyIntervals() {
    setBusy(true); setErr(null);
    try {
      const res = await api.connectIntervals(athleteId, intervalsKey);
      setVerifiedName(res.athlete_name);
      api.syncIntervals().catch(() => {}); // background
    } catch (e) {
      setErr((e as Error).message);
    } finally { setBusy(false); }
  }

  async function saveLlmAndFinish() {
    setBusy(true); setErr(null);
    try {
      if (llmKey) {
        const res = await api.testLlmKey(provider, llmKey, false);
        if (!res.is_valid) throw new Error(res.error ?? "key invalid");
      }
      await supabase.from("user_profiles").update({
        onboarding: {
          goal, experience, days, session_duration: duration, equipment,
          race_result: raceResult || undefined, injury_history: injury || undefined,
        },
        onboarding_complete: true,
        active_llm_provider: provider,
      }).neq("id", "");
      router.push("/dashboard");
      router.refresh();
    } catch (e) {
      setErr((e as Error).message);
    } finally { setBusy(false); }
  }

  return (
    <main className="mx-auto max-w-md p-4 py-10">
      <div className="mb-6 flex items-center gap-2">
        {[1, 2, 3].map((s) => (
          <div key={s} className={cn("h-1.5 flex-1 rounded-full", s <= step ? "bg-primary" : "bg-secondary")} />
        ))}
      </div>

      {step === 1 && (
        <Card>
          <CardHeader>
            <CardTitle>Connect Intervals.icu</CardTitle>
            <CardDescription>This is the bridge to your Amazfit watch. Find your athlete ID and API key in Intervals.icu → Settings → Developer.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <Input placeholder="Athlete ID (e.g. i123456)" value={athleteId} onChange={(e) => setAthleteId(e.target.value)} />
            <Input type="password" placeholder="API key" value={intervalsKey} onChange={(e) => setIntervalsKey(e.target.value)} />
            {verifiedName ? (
              <p className="flex items-center gap-2 text-sm text-primary"><CheckCircle2 className="h-4 w-4" /> Connected as {verifiedName}</p>
            ) : (
              <Button className="w-full" disabled={busy || !athleteId || !intervalsKey} onClick={verifyIntervals}>
                {busy ? "Verifying…" : "Verify & connect"}
              </Button>
            )}
            {err && <p className="text-xs text-red-400">{err}</p>}
            <div className="flex justify-between pt-2">
              <button className="text-xs text-muted-foreground hover:text-foreground" onClick={() => setStep(2)}>Skip for now</button>
              <Button size="sm" disabled={!verifiedName} onClick={() => setStep(2)}>Next</Button>
            </div>
          </CardContent>
        </Card>
      )}

      {step === 2 && (
        <Card>
          <CardHeader><CardTitle>Training profile</CardTitle><CardDescription>Tunes every AI workout to you.</CardDescription></CardHeader>
          <CardContent className="space-y-4">
            <Field label="Goal">
              <select className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm" value={goal} onChange={(e) => setGoal(e.target.value)}>
                {GOALS.map((g) => <option key={g} value={g}>{g}</option>)}
              </select>
            </Field>
            <Field label="Experience">
              <div className="flex gap-2">
                {(["Beginner", "Intermediate", "Advanced"] as const).map((x) => (
                  <Button key={x} size="sm" variant={experience === x ? "default" : "outline"} onClick={() => setExperience(x)}>{x}</Button>
                ))}
              </div>
            </Field>
            <Field label="Available days">
              <div className="flex flex-wrap gap-1.5">
                {DAYS.map((d) => (
                  <Button key={d} size="sm" variant={days.includes(d) ? "default" : "outline"}
                    onClick={() => setDays(days.includes(d) ? days.filter((x) => x !== d) : [...days, d])}>{d}</Button>
                ))}
              </div>
            </Field>
            <Field label="Session duration">
              <div className="flex gap-2">
                {DURATIONS.map((d) => (
                  <Button key={d} size="sm" variant={duration === d ? "default" : "outline"} onClick={() => setDuration(d)}>{d}m</Button>
                ))}
              </div>
            </Field>
            <Field label="Equipment">
              <select className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm" value={equipment} onChange={(e) => setEquipment(e.target.value)}>
                {EQUIPMENT.map((x) => <option key={x} value={x}>{x}</option>)}
              </select>
            </Field>
            <Field label="Recent race / best effort (optional)">
              <Input placeholder="e.g. 10K in 44:30" value={raceResult} onChange={(e) => setRaceResult(e.target.value)} />
            </Field>
            <Field label="Injury history (optional)">
              <Input placeholder="e.g. left knee — avoid deep lunges" value={injury} onChange={(e) => setInjury(e.target.value)} />
            </Field>
            <div className="flex justify-between pt-2">
              <Button size="sm" variant="ghost" onClick={() => setStep(1)}>Back</Button>
              <Button size="sm" onClick={() => setStep(3)}>Next</Button>
            </div>
          </CardContent>
        </Card>
      )}

      {step === 3 && (
        <Card>
          <CardHeader>
            <CardTitle>AI setup</CardTitle>
            <CardDescription>Workouts are generated by an LLM with your own API key. Groq and Gemini have generous free tiers — start there at zero cost.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="space-y-2">
              {(["groq", "gemini", "deepseek", "openai", "anthropic"] as LlmProvider[]).map((p) => (
                <button key={p} onClick={() => setProvider(p)}
                  className={cn("flex w-full items-center justify-between rounded-md border px-3 py-2 text-sm",
                    provider === p ? "border-primary" : "border-border")}>
                  <span className="flex items-center gap-2">
                    {PROVIDER_LABELS[p]}
                    {FREE_TIER.has(p) && <Badge variant="success">free tier</Badge>}
                  </span>
                  <a href={PROVIDER_FREE_KEY_URL[p]} target="_blank" rel="noreferrer"
                    onClick={(e) => e.stopPropagation()}
                    className="inline-flex items-center gap-1 text-xs text-primary hover:underline">
                    Get key <ExternalLink className="h-3 w-3" />
                  </a>
                </button>
              ))}
            </div>
            <Input type="password" placeholder={`${PROVIDER_LABELS[provider]} API key`} value={llmKey} onChange={(e) => setLlmKey(e.target.value)} />
            {err && <p className="text-xs text-red-400">{err}</p>}
            <div className="flex justify-between pt-2">
              <Button size="sm" variant="ghost" onClick={() => setStep(2)}>Back</Button>
              <Button size="sm" disabled={busy || !llmKey} onClick={saveLlmAndFinish}>
                {busy ? "Finishing…" : "Finish"}
              </Button>
            </div>
            <button className="w-full text-center text-xs text-muted-foreground hover:text-foreground" onClick={saveLlmAndFinish}>
              Skip — set up AI later (uses Groq default)
            </button>
          </CardContent>
        </Card>
      )}
    </main>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <label className="text-xs font-medium text-muted-foreground">{label}</label>
      {children}
    </div>
  );
}
