// ============================================================================
// Structured, greppable logging for edge functions.
//
// Emits one JSON line per event to console.* so it shows up cleanly in
// `supabase functions logs <name>` and the dashboard:
//   {"t":"2026-06-29T10:11:00.000Z","lvl":"info","fn":"generate-workout","msg":"llm","provider":"groq","ms":842}
//
// Verbosity is gated by the WM_LOG env var:
//   WM_LOG=debug   -> debug + info + warn + error
//   (unset/other)  -> info + warn + error           (prod default: quiet but useful)
//   WM_LOG=silent  -> nothing
//
// Zero dependencies. Usage:
//   const log = logger("generate-workout");
//   log.info("start", { userId });
//   const result = await log.time("llm", llmGenerate(provider, args), r => ({ provider, model: r.model }));
// ============================================================================

type Level = "debug" | "info" | "warn" | "error";
type Fields = Record<string, unknown>;

const ORDER: Record<Level, number> = { debug: 10, info: 20, warn: 30, error: 40 };

function threshold(): number {
  const v = (globalThis as { Deno?: { env: { get(k: string): string | undefined } } })
    .Deno?.env.get("WM_LOG")?.toLowerCase();
  if (v === "silent") return 100;
  if (v === "debug") return ORDER.debug;
  return ORDER.info; // default
}

export interface Logger {
  debug(msg: string, fields?: Fields): void;
  info(msg: string, fields?: Fields): void;
  warn(msg: string, fields?: Fields): void;
  error(msg: string, fields?: Fields): void;
  /** Await `p`, logging its elapsed ms + ok/err. `onOk` can pull fields off the result. */
  time<T>(msg: string, p: Promise<T>, onOk?: (r: T) => Fields): Promise<T>;
}

export function logger(fn: string): Logger {
  const emit = (lvl: Level, msg: string, fields?: Fields) => {
    if (ORDER[lvl] < threshold()) return;
    const line = JSON.stringify({ t: new Date().toISOString(), lvl, fn, msg, ...fields });
    (lvl === "error" ? console.error : lvl === "warn" ? console.warn : console.log)(line);
  };
  return {
    debug: (m, f) => emit("debug", m, f),
    info: (m, f) => emit("info", m, f),
    warn: (m, f) => emit("warn", m, f),
    error: (m, f) => emit("error", m, f),
    async time(msg, p, onOk) {
      const started = Date.now();
      try {
        const r = await p;
        emit("info", msg, { ms: Date.now() - started, ok: true, ...(onOk?.(r) ?? {}) });
        return r;
      } catch (e) {
        emit("error", msg, { ms: Date.now() - started, ok: false, err: String(e instanceof Error ? e.message : e) });
        throw e;
      }
    },
  };
}
