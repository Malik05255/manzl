import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const REVISION = "2026-09-08-render-cloudflare-v1";
const LONG_AUDIO_MS = 15 * 60_000;
const LEGACY_FUNCTION = "movie-translate";

type RuntimeConfig = Record<string, string>;

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  try {
    const contentType = req.headers.get("content-type") || "";
    if (contentType.includes("multipart/form-data")) {
      const form = await req.formData();
      if (String(form.get("mode") || "") !== "asr") return proxyLegacyForm(req, form);
      return handleAsr(req, form);
    }

    if (contentType.includes("application/json")) {
      const body = await req.json();
      if (String(body?.mode || "") === "poll" && String(body?.kind || "") === "asr") {
        return handleAsrPoll(req, body);
      }
      return proxyLegacyJson(req, body);
    }

    return json({ error: "unsupported_content_type" }, 415);
  } catch (error) {
    console.error("movie-gateway", error);
    return json({
      error: "gateway_temporarily_unavailable",
      message: "تعذر الوصول إلى بوابة الترجمة مؤقتًا. ستتم إعادة المحاولة تلقائيًا.",
      revision: REVISION,
    }, 503);
  }
});

async function handleAsr(req: Request, form: FormData): Promise<Response> {
  const config = await runtimeConfig();
  const renderAsr = cleanBase(config.render_asr_url);
  const renderNormalizer = cleanBase(config.render_normalizer_url);
  const cloudflare = cleanBase(config.cloudflare_worker_url);
  const token = config.audio_extractor_token || "";
  const durationMs = Number(form.get("duration_ms") || 0);

  // Long audio uses the durable R2 + Workflow path when Cloudflare is configured.
  if (cloudflare && renderAsr && renderNormalizer && token && durationMs >= LONG_AUDIO_MS) {
    try {
      const durableForm = cloneForm(form);
      durableForm.set("render_asr_url", renderAsr);
      durableForm.set("render_normalizer_url", renderNormalizer);
      const response = await fetch(`${cloudflare}/asr`, {
        method: "POST",
        headers: { "x-audio-extractor-token": token },
        body: durableForm,
      });
      if (response.ok) {
        const root = await safeJson(response);
        if (String(root?.status || "") === "completed") return json(root);
        const id = String(root?.job_id || "");
        if (id) {
          return json({
            status: "in_progress",
            job_id: `cf:${id}`,
            provider: "cloudflare-workflow+r2",
            revision: REVISION,
          }, 202);
        }
      }
      console.warn("Cloudflare ASR route unavailable; falling back to Render/Supabase", response.status);
    } catch (error) {
      console.warn("Cloudflare ASR route failed", error instanceof Error ? error.message : String(error));
    }
  }

  // Fast path: Render ASR. It has its own safe fallback to the existing Supabase ASR endpoint.
  if (renderAsr && token) {
    try {
      const response = await fetch(`${renderAsr}/asr`, {
        method: "POST",
        headers: { "x-audio-extractor-token": token },
        body: cloneForm(form),
      });
      if (response.ok) return passthrough(response, "render-asr");
      console.warn("Render ASR unavailable; using legacy function", response.status);
    } catch (error) {
      console.warn("Render ASR failed", error instanceof Error ? error.message : String(error));
    }
  }

  return proxyLegacyForm(req, form);
}

async function handleAsrPoll(req: Request, body: any): Promise<Response> {
  const jobId = String(body?.job_id || "");
  if (!jobId.startsWith("cf:")) return proxyLegacyJson(req, body);

  const config = await runtimeConfig();
  const cloudflare = cleanBase(config.cloudflare_worker_url);
  const token = config.audio_extractor_token || "";
  if (!cloudflare || !token) {
    return json({ status: "in_progress", provider: "cloudflare-workflow+r2", retry_after_ms: 8_000 });
  }

  try {
    const id = jobId.slice(3);
    const response = await fetch(`${cloudflare}/jobs/${encodeURIComponent(id)}`, {
      headers: { "x-audio-extractor-token": token, accept: "application/json" },
    });
    if (!response.ok) {
      if (response.status >= 500 || response.status === 429) {
        return json({ status: "in_progress", provider: "cloudflare-workflow+r2", retry_after_ms: 8_000 });
      }
      return json({ status: "failed", message: "تعذر العثور على مهمة التعرف على الصوت." });
    }
    const root = await safeJson(response);
    const status = String(root?.status || "");
    if (status === "completed") return json(root);
    if (status === "failed" || status === "cancelled") return json(root);
    return json({
      status: "in_progress",
      provider: "cloudflare-workflow+r2",
      retry_after_ms: Number(root?.retry_after_ms || 5_000),
    });
  } catch (error) {
    console.warn("Cloudflare poll failed", error instanceof Error ? error.message : String(error));
    return json({ status: "in_progress", provider: "cloudflare-workflow+r2", retry_after_ms: 8_000 });
  }
}

async function proxyLegacyForm(req: Request, form: FormData): Promise<Response> {
  const response = await fetch(legacyUrl(), {
    method: "POST",
    headers: forwardAuthHeaders(req),
    body: cloneForm(form),
  });
  return passthrough(response, "supabase-direct");
}

async function proxyLegacyJson(req: Request, body: unknown): Promise<Response> {
  const response = await fetch(legacyUrl(), {
    method: "POST",
    headers: { ...forwardAuthHeaders(req), "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return passthrough(response, "supabase-direct");
}

async function passthrough(response: Response, route: string): Promise<Response> {
  const text = await response.text();
  const headers = { ...corsHeaders(), "content-type": response.headers.get("content-type") || "application/json; charset=utf-8", "x-movie-route": route };
  return new Response(text, { status: response.status, headers });
}

function forwardAuthHeaders(req: Request): Record<string, string> {
  const headers: Record<string, string> = { accept: "application/json" };
  const authorization = req.headers.get("authorization");
  const apikey = req.headers.get("apikey");
  if (authorization) headers.authorization = authorization;
  if (apikey) headers.apikey = apikey;
  return headers;
}

async function runtimeConfig(): Promise<RuntimeConfig> {
  const auth = serverAuth();
  if (!auth) return {};
  try {
    const response = await fetch(`${auth.url}/rest/v1/movie_translator_runtime_config?select=key,value&enabled=eq.true`, {
      headers: { apikey: auth.key, ...(auth.legacyJwt ? { authorization: `Bearer ${auth.key}` } : {}) },
    });
    if (!response.ok) return {};
    const rows = await response.json();
    const out: RuntimeConfig = {};
    if (Array.isArray(rows)) {
      for (const row of rows) {
        const key = String(row?.key || "");
        const value = String(row?.value || "");
        if (key && value) out[key] = value;
      }
    }
    return out;
  } catch {
    return {};
  }
}

function serverAuth(): { url: string; key: string; legacyJwt: boolean } | null {
  const url = Deno.env.get("SUPABASE_URL") || "";
  const secretMap = parseKeyMap(Deno.env.get("SUPABASE_SECRET_KEYS"));
  const modern = secretMap.default || Object.values(secretMap)[0] || "";
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  const key = modern || legacy;
  return url && key ? { url, key, legacyJwt: key.startsWith("eyJ") } : null;
}

function legacyUrl(): string {
  return `${String(Deno.env.get("SUPABASE_URL") || "").replace(/\/$/, "")}/functions/v1/${LEGACY_FUNCTION}`;
}

function cloneForm(source: FormData): FormData {
  const out = new FormData();
  source.forEach((value, key) => out.append(key, value));
  return out;
}

function cleanBase(value: string | undefined): string {
  const raw = String(value || "").trim();
  return /^https:\/\//i.test(raw) ? raw.replace(/\/$/, "") : "";
}

function parseKeyMap(value: string | undefined): Record<string, string> {
  if (!value) return {};
  try {
    const root = JSON.parse(value);
    if (!root || typeof root !== "object" || Array.isArray(root)) return {};
    const out: Record<string, string> = {};
    for (const [name, candidate] of Object.entries(root)) if (typeof candidate === "string" && candidate) out[name] = candidate;
    return out;
  } catch {
    return {};
  }
}

async function safeJson(response: Response): Promise<any> {
  const text = await response.text();
  try { return JSON.parse(text || "{}"); } catch { return {}; }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders(), "content-type": "application/json; charset=utf-8" } });
}

function corsHeaders(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
    "access-control-allow-methods": "POST, OPTIONS",
  };
}
