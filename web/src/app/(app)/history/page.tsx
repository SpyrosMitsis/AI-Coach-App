"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { createClient } from "@/lib/supabase-browser";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { fmtCost } from "@/lib/utils";
import type { GenerationLog } from "@shared/types";

export default function HistoryPage() {
  const supabase = createClient();
  const [open, setOpen] = useState<string | null>(null);

  const logs = useQuery({
    queryKey: ["generation-logs"],
    queryFn: async () => {
      const { data, error } = await supabase
        .from("generation_logs")
        .select("*")
        .order("created_at", { ascending: false })
        .limit(50);
      if (error) throw error;
      return data as GenerationLog[];
    },
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Generation History</h1>
      <p className="text-sm text-muted-foreground">
        Every AI generation, with the provider, tokens, cost, prompt and raw response — useful for comparing providers.
      </p>

      {(logs.data ?? []).map((log) => (
        <Card key={log.id}>
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center justify-between text-sm">
              <span className="flex items-center gap-2">
                <Badge variant={log.parsed_ok ? "success" : "danger"}>{log.parsed_ok ? "parsed" : "failed"}</Badge>
                {log.provider} · {log.model}
              </span>
              <span className="font-normal text-muted-foreground">{new Date(log.created_at).toLocaleString()}</span>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
              <span>prompt: {log.prompt_tokens ?? "—"} tok</span>
              <span>completion: {log.completion_tokens ?? "—"} tok</span>
              <span>cost: {fmtCost(log.estimated_cost_usd)}</span>
            </div>
            <Button size="sm" variant="outline" onClick={() => setOpen(open === log.id ? null : log.id)}>
              {open === log.id ? "Hide details" : "Show prompt + response"}
            </Button>
            {open === log.id && (
              <div className="space-y-2 text-xs">
                <Section label="User prompt" body={log.user_prompt} />
                <Section label="Raw response" body={log.raw_response} />
                <Section label="System prompt" body={log.system_prompt} />
              </div>
            )}
          </CardContent>
        </Card>
      ))}
      {logs.data?.length === 0 && <p className="text-sm text-muted-foreground">No generations yet.</p>}
    </div>
  );
}

function Section({ label, body }: { label: string; body: string }) {
  return (
    <div>
      <p className="mb-1 font-medium text-foreground">{label}</p>
      <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-md bg-secondary/50 p-2 text-muted-foreground">
        {body}
      </pre>
    </div>
  );
}
