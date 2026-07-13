export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

// HTTP status for a caught error: typed errors (e.g. QuotaError's 429) carry
// a numeric `status`; anything else is a plain 500.
export function errorStatus(e: unknown): number {
  const s = (e as { status?: unknown })?.status;
  return typeof s === "number" && s >= 400 && s <= 599 ? s : 500;
}

export function handleOptions(req: Request): Response | null {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  return null;
}
