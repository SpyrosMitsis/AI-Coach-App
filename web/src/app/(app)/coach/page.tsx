"use client";

import { useEffect, useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Send, Sparkles, Wrench } from "lucide-react";

interface Msg {
  role: "user" | "assistant";
  content: string;
  tools?: string[];
}

const SUGGESTIONS = [
  "How is my training going?",
  "Plan my next week",
  "I feel tired — should I still train today?",
  "Build me a workout for tomorrow",
];

export default function CoachPage() {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [conversationId, setConversationId] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const send = useMutation({
    mutationFn: (thread: Msg[]) =>
      api.coachChat(thread.map(({ role, content }) => ({ role, content })), conversationId),
    onSuccess: (res) => {
      setConversationId(res.conversation_id);
      setMessages((m) => [...m, { role: "assistant", content: res.reply, tools: res.tools_used }]);
    },
    onError: (e) => {
      setMessages((m) => [...m, { role: "assistant", content: `⚠️ ${(e as Error).message}` }]);
    },
  });

  const submit = (text: string) => {
    const t = text.trim();
    if (!t || send.isPending) return;
    const next: Msg[] = [...messages, { role: "user" as const, content: t }];
    setMessages(next);
    setInput("");
    send.mutate(next);
  };

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, send.isPending]);

  return (
    <div className="flex h-[calc(100vh-7.5rem)] flex-col">
      <header className="mb-3">
        <h1 className="text-2xl font-bold">Coach</h1>
        <p className="text-sm text-muted-foreground">
          Reads your real training data; can plan weeks and create workouts.
        </p>
      </header>

      <div className="flex-1 space-y-3 overflow-y-auto pr-1">
        {messages.length === 0 && (
          <div className="mt-8 space-y-2">
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <Sparkles className="h-4 w-4" /> Try asking:
            </p>
            {SUGGESTIONS.map((s) => (
              <button
                key={s}
                onClick={() => submit(s)}
                className="block w-full rounded-lg border border-border bg-card px-3 py-2 text-left text-sm hover:bg-secondary"
              >
                {s}
              </button>
            ))}
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={cn("flex", m.role === "user" ? "justify-end" : "justify-start")}>
            <div
              className={cn(
                "max-w-[85%] whitespace-pre-wrap rounded-2xl px-4 py-2.5 text-sm",
                m.role === "user"
                  ? "rounded-br-sm bg-primary text-primary-foreground"
                  : "rounded-bl-sm bg-card border border-border",
              )}
            >
              {m.content}
              {m.tools && m.tools.length > 0 && (
                <p className="mt-2 flex items-center gap-1 text-[11px] text-muted-foreground">
                  <Wrench className="h-3 w-3" />
                  {[...new Set(m.tools)].join(" · ")}
                </p>
              )}
            </div>
          </div>
        ))}
        {send.isPending && (
          <div className="flex justify-start">
            <div className="rounded-2xl rounded-bl-sm border border-border bg-card px-4 py-3">
              <span className="inline-flex gap-1">
                <Dot delay="0ms" /> <Dot delay="150ms" /> <Dot delay="300ms" />
              </span>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <form
        className="mt-3 flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          submit(input);
        }}
      >
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit(input);
            }
          }}
          rows={1}
          placeholder="Ask your coach anything…"
          className="max-h-32 min-h-[2.75rem] flex-1 resize-none rounded-xl border border-border bg-card px-3 py-2.5 text-sm outline-none focus:ring-1 focus:ring-primary"
        />
        <Button type="submit" size="icon" disabled={send.isPending || !input.trim()}>
          <Send className="h-4 w-4" />
        </Button>
      </form>
    </div>
  );
}

function Dot({ delay }: { delay: string }) {
  return (
    <span
      className="h-1.5 w-1.5 animate-bounce rounded-full bg-muted-foreground"
      style={{ animationDelay: delay }}
    />
  );
}
