import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {
  isSafeRemoteMediaUrl,
  makeRoutePlan,
  normalizeLanguage,
  normalizeRetention,
  retentionExpiry,
} from "../_shared/smart-media-router.ts";

const API_REVISION = "2026-09-08-smart-media-v2-durable";

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  try {
    const body = await req.json();
    const mode = String(body?.mode || "plan");
    if (mode === "plan") return handlePlan(body);
    if (mode === "translate_url") return handleTranslateUrl(body);
    if (mode === "get_job") return handleGetJob(body);
    if (mode === "list_jobs") return handleListJobs(body);
    return json({ error: "invalid_mode" }, 400);
  } catch (error) {
    console.error("media-gateway", error);
    return json({
      error: "gateway_temporarily_unavailable",
      message: error instanceof Error ? error.message : "تعذر تشغيل بوابة H AI حاليًا.",
    }, 503);
  }
});

function handlePlan(body: any): Response {
  const sourceKind = body?.source_kind === "local" ? "local" : "url";
  const plan = makeRoutePlan({
    sourceKind,
    durationMs: Number(body?.duration_ms || 0),
    language: body?.language,
    retention: body?.retention,
  });
  return json({ status: "ok", revision: API_REVISION, plan });
}

async function handleTranslateUrl(body: any): Promise<Response> {
  const sourceUrl = String(body?.source_url || "").trim();
  if (!isSafeRemoteMediaUrl(sourceUrl)) {
    return json({ error: "invalid_source_url", message: "الرابط يجب أن يكون رابط HTTP/HTTPS مباشرًا وقابلًا للوصول." }, 400);
  }

  const orchestratorUrl = String(Deno.env.get("SMART_MEDIA_ORCHESTRATOR_URL") || "").replace(/\/$/, "");
  const orchestratorToken = String(Deno.env.get("SMART_MEDIA_ORCHESTRATOR_TOKEN") || "");
  if (!orchestratorUrl || !orchestratorToken) {
    return json({
      error: "orchestrator_not_configured",
      message: "مسار المعالجة الطويلة غير مفعّل بعد على خادم التطوير.",
    }, 503);
  }

  const clientId = sanitizeClientId(body?.client_id);
  const title = sanitizeTitle(body?.title || titleFromUrl(sourceUrl));
  const durationMs = Math.max(0, Number(body?.duration_ms || 0));
  const language = normalizeLanguage(body?.language);
  const retention = normalizeRetention(body?.retention);
  const jobId = crypto.randomUUID();

  await upsertJob(jobId, {
    client_id: clientId,
    title,
    source_kind: "url",
    source_url: sourceUrl,
    playback_url: sourceUrl,
    retention,
    expires_at: retentionExpiry(retention),
    source_language: language === "auto" ? null : language,
    target_language: "ar",
    status: "queued",
    progress: 0,
    stage: "في قائمة المعالجة",
    provider_trace: [],
  });

  const response = await fetch(`${orchestratorUrl}/jobs`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-orchestrator-token": orchestratorToken,
    },
    body: JSON.stringify({
      job_id: jobId,
      source_url: sourceUrl,
      title,
      retention,
      language,
      duration_ms: durationMs,
    }),
  });
  const text = await response.text();
  if (!response.ok) {
    await patchJob(jobId, {
      status: "failed",
      stage: "تعذر بدء المعالجة السحابية",
      error: text.slice(0, 500),
    });
    return json({ error: "orchestrator_rejected", message: "تعذر بدء المعالجة الطويلة. حاول لاحقًا." }, 503);
  }

  return json({
    status: "accepted",
    revision: API_REVISION,
    job_id: jobId,
    title,
    source_url: sourceUrl,
    playback_url: sourceUrl,
    retention,
    expires_at: retentionExpiry(retention),
    plan: makeRoutePlan({ sourceKind: "url", durationMs, language, retention }),
  }, 202);
}

async function handleGetJob(body: any): Promise<Response> {
  const id = String(body?.job_id || "");
  const clientId = sanitizeClientId(body?.client_id);
  if (!id) return json({ error: "job_id_required" }, 400);
  const rows = await dbSelect(`media_jobs?id=eq.${encodeURIComponent(id)}&client_id=eq.${encodeURIComponent(clientId)}&limit=1`);
  return json({ status: "ok", job: Array.isArray(rows) ? rows[0] || null : null });
}

async function handleListJobs(body: any): Promise<Response> {
  const clientId = sanitizeClientId(body?.client_id);
  const rows = await dbSelect(`media_jobs?client_id=eq.${encodeURIComponent(clientId)}&order=created_at.desc&limit=50`);
  return json({ status: "ok", jobs: Array.isArray(rows) ? rows : [] });
}

async function upsertJob(id: string, values: Record<string, unknown>) {
  await dbWrite("POST", "media_jobs?on_conflict=id", [{ id, ...values }], "resolution=merge-duplicates");
}

async function patchJob(id: string, values: Record<string, unknown>) {
  await dbWrite("PATCH", `media_jobs?id=eq.${encodeURIComponent(id)}`, {
    ...values,
    updated_at: new Date().toISOString(),
  });
}

async function dbSelect(path: string): Promise<any> {
  const auth = serverAuth();
  if (!auth) throw new Error("Supabase server credentials unavailable");
  const response = await fetch(`${auth.url}/rest/v1/${path}`, {
    headers: { apikey: auth.key, Authorization: `Bearer ${auth.key}` },
  });
  if (!response.ok) throw new Error(`database_read_${response.status}`);
  return response.json();
}

async function dbWrite(method: string, path: string, body: unknown, prefer?: string): Promise<void> {
  const auth = serverAuth();
  if (!auth) throw new Error("Supabase server credentials unavailable");
  const response = await fetch(`${auth.url}/rest/v1/${path}`, {
    method,
    headers: {
      apikey: auth.key,
      Authorization: `Bearer ${auth.key}`,
      "content-type": "application/json",
      ...(prefer ? { Prefer: prefer } : {}),
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`database_write_${response.status}:${(await response.text()).slice(0, 200)}`);
}

function serverAuth(): { url: string; key: string } | null {
  const url = Deno.env.get("SUPABASE_URL") || "";
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  return url && key ? { url, key } : null;
}

function sanitizeClientId(value: unknown): string {
  const id = String(value || "anonymous").replace(/[^a-zA-Z0-9._-]/g, "").slice(0, 80);
  return id || "anonymous";
}

function sanitizeTitle(value: unknown): string {
  return String(value || "Movie").replace(/\s+/g, " ").trim().slice(0, 180) || "Movie";
}

function titleFromUrl(value: string): string {
  try {
    return decodeURIComponent(new URL(value).pathname.split("/").filter(Boolean).pop() || "Movie").slice(0, 180);
  } catch {
    return "Movie";
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders(), "content-type": "application/json; charset=utf-8" },
  });
}

function corsHeaders(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
    "access-control-allow-methods": "POST, OPTIONS",
  };
}
