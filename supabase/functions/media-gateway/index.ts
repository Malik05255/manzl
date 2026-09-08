import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {
  isSafeRemoteMediaUrl,
  makeRoutePlan,
  normalizeLanguage,
  normalizeRetention,
  retentionExpiry,
} from "../_shared/smart-media-router.ts";

const API_REVISION = "2026-09-08-smart-media-v4-zero-bill";
const ACCOUNT_KEY_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;
const DEFAULT_DAILY_JOB_LIMIT = 5;

type ServerAuth = { url: string; key: string; legacyJwt: boolean };
type DailyQuota = { allowed: boolean; used: number; limit: number };

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!validProjectApiKey(req)) {
    return json({ error: "invalid_api_key", message: "مفتاح مشروع H AI غير صالح." }, 401);
  }

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
  return json({ status: "ok", revision: API_REVISION, plan, daily_job_limit: dailyJobLimit() });
}

async function handleTranslateUrl(body: any): Promise<Response> {
  const sourceUrl = String(body?.source_url || "").trim();
  if (!isSafeRemoteMediaUrl(sourceUrl)) {
    return json({ error: "invalid_source_url", message: "الرابط يجب أن يكون رابط HTTP/HTTPS مباشرًا وقابلًا للوصول." }, 400);
  }

  const clientId = await resolvePrivateClientId(body);
  if (!clientId) return privateKeyRequired();

  const orchestratorUrl = String(Deno.env.get("SMART_MEDIA_ORCHESTRATOR_URL") || "").replace(/\/$/, "");
  const orchestratorToken = String(Deno.env.get("SMART_MEDIA_ORCHESTRATOR_TOKEN") || "");
  if (!orchestratorUrl || !orchestratorToken) {
    return json({
      error: "orchestrator_not_configured",
      message: "مسار المعالجة الطويلة غير مفعّل بعد على خادم التطوير.",
    }, 503);
  }

  // Hard free-tier guard. A cloud movie job cannot start after the daily cap.
  const quota = await reserveDailySlot();
  if (!quota.allowed) {
    return json({
      error: "daily_free_limit_reached",
      message: `اكتمل حد H AI المجاني اليوم (${quota.limit} أفلام). جرّب غدًا.`,
      used: quota.used,
      limit: quota.limit,
    }, 429);
  }

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
    quota: { used: quota.used, limit: quota.limit, remaining: Math.max(0, quota.limit - quota.used) },
    plan: makeRoutePlan({ sourceKind: "url", durationMs, language, retention }),
  }, 202);
}

async function handleGetJob(body: any): Promise<Response> {
  const id = String(body?.job_id || "");
  if (!id) return json({ error: "job_id_required" }, 400);
  const clientId = await resolvePrivateClientId(body);
  if (!clientId) return privateKeyRequired();
  const rows = await dbSelect(`media_jobs?id=eq.${encodeURIComponent(id)}&client_id=eq.${encodeURIComponent(clientId)}&limit=1`);
  return json({ status: "ok", job: Array.isArray(rows) ? rows[0] || null : null });
}

async function handleListJobs(body: any): Promise<Response> {
  const clientId = await resolvePrivateClientId(body);
  if (!clientId) return privateKeyRequired();
  const rows = await dbSelect(`media_jobs?client_id=eq.${encodeURIComponent(clientId)}&order=created_at.desc&limit=50`);
  return json({ status: "ok", jobs: Array.isArray(rows) ? rows : [] });
}

async function reserveDailySlot(): Promise<DailyQuota> {
  const auth = serverAuth();
  if (!auth) throw new Error("Supabase server credentials unavailable");
  const limit = dailyJobLimit();
  const response = await fetch(`${auth.url}/rest/v1/rpc/reserve_media_daily_slot`, {
    method: "POST",
    headers: restHeaders(auth),
    body: JSON.stringify({ p_limit: limit }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`quota_reservation_${response.status}:${text.slice(0, 200)}`);
  const root = JSON.parse(text || "[]");
  const row = Array.isArray(root) ? root[0] : root;
  return {
    allowed: Boolean(row?.allowed),
    used: Math.max(0, Number(row?.used || 0)),
    limit: Math.max(1, Number(row?.quota_limit || limit)),
  };
}

function dailyJobLimit(): number {
  const raw = Number(Deno.env.get("SMART_MEDIA_DAILY_JOB_LIMIT") || DEFAULT_DAILY_JOB_LIMIT);
  return Number.isFinite(raw) ? Math.max(1, Math.min(100, Math.floor(raw))) : DEFAULT_DAILY_JOB_LIMIT;
}

/**
 * The raw personal sync key is never persisted server-side. Only its SHA-256-derived
 * account id is used to partition private movie jobs between Android and web.
 */
async function resolvePrivateClientId(body: any): Promise<string | null> {
  const accountKey = String(body?.account_key || "").trim();
  if (ACCOUNT_KEY_PATTERN.test(accountKey)) {
    const bytes = new TextEncoder().encode(`h-ai-personal-account-v1:${accountKey}`);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    return `acct-${toHex(digest).slice(0, 48)}`;
  }

  if (Deno.env.get("SMART_MEDIA_ALLOW_LEGACY_CLIENT_ID") === "true") {
    const legacy = sanitizeClientId(body?.client_id);
    return legacy === "anonymous" ? null : legacy;
  }
  return null;
}

function privateKeyRequired(): Response {
  return json({
    error: "personal_key_required",
    message: "مفتاح H AI الشخصي مطلوب للوصول إلى مكتبتك السحابية.",
  }, 401);
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
  const response = await fetch(`${auth.url}/rest/v1/${path}`, { headers: restHeaders(auth, false) });
  if (!response.ok) throw new Error(`database_read_${response.status}`);
  return response.json();
}

async function dbWrite(method: string, path: string, body: unknown, prefer?: string): Promise<void> {
  const auth = serverAuth();
  if (!auth) throw new Error("Supabase server credentials unavailable");
  const response = await fetch(`${auth.url}/rest/v1/${path}`, {
    method,
    headers: { ...restHeaders(auth), ...(prefer ? { Prefer: prefer } : {}) },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`database_write_${response.status}:${(await response.text()).slice(0, 200)}`);
}

function restHeaders(auth: ServerAuth, jsonBody = true): Record<string, string> {
  return {
    apikey: auth.key,
    ...(auth.legacyJwt ? { Authorization: `Bearer ${auth.key}` } : {}),
    ...(jsonBody ? { "content-type": "application/json" } : {}),
  };
}

function serverAuth(): ServerAuth | null {
  const url = Deno.env.get("SUPABASE_URL") || "";
  const secretMap = parseKeyMap(Deno.env.get("SUPABASE_SECRET_KEYS"));
  const modernSecret = secretMap.default || Object.values(secretMap)[0] || "";
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  const key = modernSecret || legacy;
  return url && key ? { url, key, legacyJwt: key.startsWith("eyJ") } : null;
}

function validProjectApiKey(req: Request): boolean {
  const supplied = String(req.headers.get("apikey") || "").trim();
  if (!supplied) return false;
  const publishable = Object.values(parseKeyMap(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS")));
  const legacyAnon = Deno.env.get("SUPABASE_ANON_KEY") || "";
  return publishable.includes(supplied) || (!!legacyAnon && supplied === legacyAnon);
}

function parseKeyMap(value: string | undefined): Record<string, string> {
  if (!value) return {};
  try {
    const root = JSON.parse(value);
    if (!root || typeof root !== "object" || Array.isArray(root)) return {};
    return Object.fromEntries(Object.entries(root).filter(([, v]) => typeof v === "string" && v));
  } catch {
    return {};
  }
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

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
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
