"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createClient } from "@/lib/supabase-browser";
import { api } from "@/lib/api";
import {
  PROVIDER_FREE_KEY_URL,
  PROVIDER_LABELS,
  PROVIDER_MODELS,
  PROVIDER_PRICING,
  type LlmProvider,
} from "@shared/types";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { fmtCost } from "@/lib/utils";
import Link from "next/link";
import { ArrowDown, ArrowUp, CheckCircle2, ChevronLeft, ExternalLink, XCircle } from "lucide-react";

const ALL: LlmProvider[] = ["anthropic", "deepseek", "openai", "gemini", "groq"];

// Rough per-generation cost: ~1500 prompt + ~700 completion tokens.
function perGenCost(p: LlmProvider): number {
  const pr = PROVIDER_PRICING[p];
  return (1500 / 1e6) * pr.inputPer1M + (700 / 1e6) * pr.outputPer1M;
}

export default function LlmSettingsPage() {
  const qc = useQueryClient();
  const supabase = createClient();

  const profile = useQuery({
    queryKey: ["profile"],
    queryFn: async () => {
      const { data, error } = await supabase
        .from("user_profiles")
        .select("active_llm_provider, llm_fallback_chain")
        .single();
      if (error) throw error;
      return data as { active_llm_provider: LlmProvider; llm_fallback_chain: LlmProvider[] };
    },
  });

  const keys = useQuery({
    queryKey: ["llm-keys"],
    queryFn: async () => {
      const { data, error } = await supabase
        .from("llm_api_keys")
        .select("provider, is_valid, last_tested_at");
      if (error) throw error;
      return data as { provider: LlmProvider; is_valid: boolean | null; last_tested_at: string | null }[];
    },
  });

  const setActive = useMutation({
    mutationFn: async (provider: LlmProvider) => {
      const { error } = await supabase.from("user_profiles").update({ active_llm_provider: provider }).neq("id", "");
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["profile"] }),
  });

  const setChain = useMutation({
    mutationFn: async (chain: LlmProvider[]) => {
      const { error } = await supabase.from("user_profiles").update({ llm_fallback_chain: chain }).neq("id", "");
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["profile"] }),
  });

  if (profile.isLoading || keys.isLoading) return <p className="text-sm text-muted-foreground">Loading…</p>;

  const active = profile.data!.active_llm_provider;
  const chain = profile.data!.llm_fallback_chain ?? [];
  const keyMap = new Map(keys.data!.map((k) => [k.provider, k]));

  function move(i: number, dir: -1 | 1) {
    const next = [...chain];
    const j = i + dir;
    if (j < 0 || j >= next.length) return;
    [next[i], next[j]] = [next[j], next[i]];
    setChain.mutate(next);
  }

  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <Link href="/settings" className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          <ChevronLeft className="h-3.5 w-3.5" /> Settings
        </Link>
        <h1 className="text-3xl font-bold tracking-tight">AI provider</h1>
        <p className="text-sm text-muted-foreground">
          Bring your own key. Keys are encrypted in Supabase and never stored on this device.
        </p>
      </header>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">Active provider</CardTitle>
          <CardDescription>Used first; the fallback chain runs if it fails.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          {ALL.map((p) => (
            <Button
              key={p}
              size="sm"
              variant={active === p ? "default" : "outline"}
              onClick={() => setActive.mutate(p)}
            >
              {PROVIDER_LABELS[p]}
            </Button>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">Fallback chain</CardTitle>
          <CardDescription>Order tried when the active provider errors.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          {chain.map((p, i) => (
            <div key={p} className="flex items-center justify-between rounded-md border border-border px-3 py-2">
              <span className="text-sm">
                {i + 1}. {PROVIDER_LABELS[p]}
              </span>
              <div className="flex gap-1">
                <Button size="icon" variant="ghost" onClick={() => move(i, -1)} disabled={i === 0}>
                  <ArrowUp className="h-4 w-4" />
                </Button>
                <Button size="icon" variant="ghost" onClick={() => move(i, 1)} disabled={i === chain.length - 1}>
                  <ArrowDown className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      <div className="space-y-3">
        {ALL.map((p) => (
          <ProviderRow key={p} provider={p} status={keyMap.get(p) ?? null} costPerGen={perGenCost(p)} />
        ))}
      </div>
    </div>
  );
}

function ProviderRow({
  provider,
  status,
  costPerGen,
}: {
  provider: LlmProvider;
  status: { is_valid: boolean | null; last_tested_at: string | null } | null;
  costPerGen: number;
}) {
  const qc = useQueryClient();
  const [key, setKey] = useState("");
  const [sample, setSample] = useState<string | null>(null);

  const test = useMutation({
    mutationFn: (sampleGen: boolean) => api.testLlmKey(provider, key, sampleGen),
    onSuccess: (res) => {
      setSample(res.sample);
      qc.invalidateQueries({ queryKey: ["llm-keys"] });
    },
  });

  const isSet = !!status;
  const valid = status?.is_valid;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center justify-between text-base">
          <span className="flex items-center gap-2">
            {PROVIDER_LABELS[provider]}
            {isSet && valid === true && <CheckCircle2 className="h-4 w-4 text-primary" />}
            {isSet && valid === false && <XCircle className="h-4 w-4 text-red-400" />}
          </span>
          <Badge variant={isSet ? (valid ? "success" : "danger") : "outline"}>
            {isSet ? (valid ? "key valid" : "key invalid") : "not set"}
          </Badge>
        </CardTitle>
        <CardDescription className="flex items-center justify-between">
          <span>{PROVIDER_MODELS[provider]} · ~{fmtCost(costPerGen)}/workout</span>
          <a
            href={PROVIDER_FREE_KEY_URL[provider]}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 text-primary hover:underline"
          >
            Get key <ExternalLink className="h-3 w-3" />
          </a>
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        <div className="flex gap-2">
          <Input
            type="password"
            placeholder={isSet ? "•••••••• (replace)" : "Paste API key"}
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
          <Button size="sm" disabled={!key || test.isPending} onClick={() => test.mutate(false)}>
            {test.isPending ? "Testing…" : "Save & Test"}
          </Button>
          <Button size="sm" variant="outline" disabled={!key || test.isPending} onClick={() => test.mutate(true)}>
            Test Generation
          </Button>
        </div>
        {test.data?.error && <p className="text-xs text-red-400">{test.data.error}</p>}
        {test.data && !test.data.error && (
          <p className="text-xs text-muted-foreground">
            Connection OK · {fmtCost(test.data.estimated_cost_usd)}
          </p>
        )}
        {sample && (
          <pre className="max-h-40 overflow-auto rounded-md bg-secondary/50 p-2 text-[11px] text-muted-foreground">
            {sample}
          </pre>
        )}
      </CardContent>
    </Card>
  );
}
