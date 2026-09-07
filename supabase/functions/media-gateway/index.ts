import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {
  isSafeRemoteMediaUrl,
  makeRoutePlan,
  normalizeLanguage,
  normalizeRetention,
  retentionExpiry,
  durationBucket,
} from "../_shared/smart-media-router.ts";

const GROQ_TRANSCRIBE_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
const GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
const GROQ_REVIEW_MODEL = "openai/gpt-oss-120b";
const GROQ_TRANSLATE_MODEL = "openai/gpt-oss-120b";
const GROQ_FAST_MODEL = "openai/gpt-oss-20b";
const API_REVISION = "2026-09-08-smart-media-v1";

type Segment = { id: number; start_ms: number; end_ms: number; text: string };
type Subtitle = { id: number; ar: string };

type ProviderTrace = {
  task: string;
  provider: string;
  latency_ms: number;
  ok: boolean;
};

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
    return json({ error: "invalid_source_url", message: "الرابط يجب أن يكون رابط فيلم HTTP/HTTPS مباشرًا وقابلًا للوصول." }, 400);
  }

  const clientId = sanitizeClientId(body?.client_id);
  const title = sanitizeTitle(body?.title || titleFromUrl(sourceUrl));
  const durationMs = Math.max(0, Number(body?.duration_ms || 0));
  const language = normalizeLanguage(body?.language);
  const retention = normalizeRetention(body?.retention);
  const plan = makeRoutePlan({ sourceKind: "url", durationMs, language, retention });
  const jobId = crypto.randomUUID();
  const trace: ProviderTrace[] = [];
  const startedAt = Date.now();

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
    status: "processing",
    progress: 0.05,
    stage: "فهم صوت الفيلم",
    started_at: new Date().toISOString(),
    provider_trace: trace,
  });

  try {
    const asr = await transcribeUrl(sourceUrl, plan.asrPrimary, language, trace)
      .catch(async () => transcribeUrl(sourceUrl, plan.asrFallback, language, trace));

    const detectedLanguage = normalizeLanguage(asr.language || language);
    const segments = normalizeSegments(asr.segments);
    if (!segments.length) throw new Error("لم يتم العثور على حوار واضح في الفيلم.");

    await patchJob(jobId, {
      source_language: detectedLanguage === "auto" ? null : detectedLanguage,
      progress: 0.55,
      stage: "ترجمة الحوار للعربية",
      transcript: segments,
      provider_trace: trace,
    });

    let subtitles = await translateAzure(segments, detectedLanguage, trace).catch(() => [] as Subtitle[]);
    if (subtitles.length !== segments.length) {
      subtitles = await translateGroq(segments, detectedLanguage, trace);
    }
    if (subtitles.length !== segments.length) throw new Error("لم ترجع منصة الترجمة جميع الأسطر.");

    const suspicious = suspiciousIds(segments, subtitles);
    if (suspicious.length) {
      subtitles = await reviewSelected(segments, subtitles, suspicious, trace).catch(() => subtitles);
    }

    const srtText = toSrt(segments, subtitles);
    const vttText = toVtt(segments, subtitles);

    await patchJob(jobId, {
      progress: 0.9,
      stage: "إنشاء الملخص والتحليل",
      subtitles,
      srt_text: srtText,
      vtt_text: vttText,
      provider_trace: trace,
    });

    const summary = await analyzeTranscript(segments, subtitles, trace).catch(() => null);
    const completedAt = new Date().toISOString();

    await patchJob(jobId, {
      status: "completed",
      progress: 1,
      stage: "جاهز للمشاهدة",
      source_language: detectedLanguage === "auto" ? null : detectedLanguage,
      subtitles,
      srt_text: srtText,
      vtt_text: vttText,
      summary,
      provider_trace: trace,
      completed_at: completedAt,
    });

    await recordTrace(trace, detectedLanguage, durationMs);

    return json({
      status: "completed",
      revision: API_REVISION,
      job_id: jobId,
      title,
      source_url: sourceUrl,
      playback_url: sourceUrl,
      retention,
      expires_at: retentionExpiry(retention),
      detected_language: detectedLanguage,
      target_language: "ar",
      segments,
      subtitles,
      srt: srtText,
      vtt: vttText,
      summary,
      providers: trace,
      processing_ms: Date.now() - startedAt,
      plan,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "تعذر إكمال ترجمة الرابط.";
    await patchJob(jobId, {
      status: "failed",
      stage: "تعذر إكمال المهمة",
      error: message,
      provider_trace: trace,
    });
    await recordTrace(trace, language, durationMs);
    return json({ error: "translation_failed", message, job_id: jobId, providers: trace }, 503);
  }
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

async function transcribeUrl(sourceUrl: string, model: string, language: string, trace: ProviderTrace[]): Promise<any> {
  const key = Deno.env.get("GROQ_API_KEY");
  if (!key) throw new Error("GROQ_API_KEY غير مفعّل.");
  const form = new FormData();
  form.append("url", sourceUrl);
  form.append("model", model);
  form.append("response_format", "verbose_json");
  form.append("temperature", "0");
  form.append("timestamp_granularities[]", "segment");
  if (language !== "auto") form.append("language", language);

  const started = Date.now();
  const response = await fetch(GROQ_TRANSCRIBE_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}` },
    body: form,
  });
  const text = await response.text();
  trace.push({ task: "asr", provider: `groq:${model}`, latency_ms: Date.now() - started, ok: response.ok });
  if (!response.ok) throw new Error(`Groq ASR ${response.status}: ${text.slice(0, 300)}`);
  return JSON.parse(text);
}

function normalizeSegments(input: any): Segment[] {
  const source = Array.isArray(input) ? input : [];
  return source.map((item: any, index: number) => {
    const startMs = Math.max(0, Math.round(Number(item?.start || 0) * 1000));
    const endMs = Math.max(startMs + 1, Math.round(Number(item?.end || 0) * 1000));
    return { id: index, start_ms: startMs, end_ms: endMs, text: clean(String(item?.text || "")) };
  }).filter((item: Segment) => item.text.length > 0);
}

async function translateAzure(segments: Segment[], language: string, trace: ProviderTrace[]): Promise<Subtitle[]> {
  const key = Deno.env.get("AZURE_TRANSLATOR_KEY");
  const region = Deno.env.get("AZURE_TRANSLATOR_REGION");
  if (!key || !region) throw new Error("Azure not configured");
  const endpoint = (Deno.env.get("AZURE_TRANSLATOR_ENDPOINT") || "https://api.cognitive.microsofttranslator.com").replace(/\/$/, "");
  const from = language !== "auto" ? `&from=${encodeURIComponent(language)}` : "";
  const batches = makeBatches(segments, 80, 40_000);
  const result: Subtitle[] = [];
  const started = Date.now();

  try {
    for (const batch of batches) {
      const response = await fetch(`${endpoint}/translate?api-version=3.0${from}&to=ar`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json; charset=UTF-8",
          "Ocp-Apim-Subscription-Key": key,
          "Ocp-Apim-Subscription-Region": region,
        },
        body: JSON.stringify(batch.map((item) => ({ Text: item.text }))),
      });
      const text = await response.text();
      if (!response.ok) throw new Error(`Azure ${response.status}: ${text.slice(0, 300)}`);
      const jsonBody = JSON.parse(text);
      if (!Array.isArray(jsonBody) || jsonBody.length !== batch.length) throw new Error("Azure result mismatch");
      batch.forEach((item, index) => result.push({ id: item.id, ar: clean(String(jsonBody[index]?.translations?.[0]?.text || "")) }));
    }
    trace.push({ task: "translate", provider: "azure-translator", latency_ms: Date.now() - started, ok: true });
    return result;
  } catch (error) {
    trace.push({ task: "translate", provider: "azure-translator", latency_ms: Date.now() - started, ok: false });
    throw error;
  }
}

async function translateGroq(segments: Segment[], language: string, trace: ProviderTrace[]): Promise<Subtitle[]> {
  const key = Deno.env.get("GROQ_API_KEY");
  if (!key) throw new Error("Groq translation not configured");
  const output: Subtitle[] = [];
  const started = Date.now();
  try {
    for (const batch of makeBatches(segments, 65, 9_000)) {
      const payload = batch.map((x) => ({ id: x.id, text: x.text }));
      const prompt = `Translate the following movie subtitles from ${language === "auto" ? "the detected language" : language} to natural Modern Standard Arabic. Preserve every id exactly once. Do not omit, merge, explain, or censor ordinary dialogue. Return JSON only: {"subtitles":[{"id":0,"ar":"..."}]}.\n${JSON.stringify(payload)}`;
      const root = await groqJson(GROQ_TRANSLATE_MODEL, prompt, key);
      const arr = Array.isArray(root?.subtitles) ? root.subtitles : [];
      arr.forEach((item: any) => {
        const id = Number(item?.id);
        const ar = clean(String(item?.ar || ""));
        if (Number.isInteger(id) && ar) output.push({ id, ar });
      });
    }
    const sorted = output.sort((a, b) => a.id - b.id);
    trace.push({ task: "translate", provider: `groq:${GROQ_TRANSLATE_MODEL}`, latency_ms: Date.now() - started, ok: sorted.length === segments.length });
    return sorted;
  } catch (error) {
    trace.push({ task: "translate", provider: `groq:${GROQ_TRANSLATE_MODEL}`, latency_ms: Date.now() - started, ok: false });
    throw error;
  }
}

function suspiciousIds(segments: Segment[], subtitles: Subtitle[]): number[] {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return segments.filter((s) => {
    const ar = map.get(s.id) || "";
    if (!ar) return true;
    if (ar.length > Math.max(180, s.text.length * 4)) return true;
    const latin = (ar.match(/[A-Za-z]/g) || []).length;
    return latin > Math.max(6, Math.round(ar.length * 0.3));
  }).map((s) => s.id).slice(0, 80);
}

async function reviewSelected(segments: Segment[], subtitles: Subtitle[], ids: number[], trace: ProviderTrace[]): Promise<Subtitle[]> {
  const key = Deno.env.get("GROQ_API_KEY");
  if (!key || !ids.length) return subtitles;
  const current = new Map(subtitles.map((x) => [x.id, x.ar]));
  const context = segments.filter((s) => ids.includes(s.id) || ids.includes(s.id - 1) || ids.includes(s.id + 1));
  const prompt = `Review only target_ids for cinematic Arabic quality and fidelity. Keep names and meaning consistent with neighboring lines. Return JSON only {"subtitles":[{"id":0,"ar":"..."}]}.\ntarget_ids=${JSON.stringify(ids)}\nsource=${JSON.stringify(context)}\ndraft=${JSON.stringify(context.map((s) => ({ id:s.id, ar: current.get(s.id) || "" })))}`;
  const started = Date.now();
  const root = await groqJson(GROQ_REVIEW_MODEL, prompt, key);
  const arr = Array.isArray(root?.subtitles) ? root.subtitles : [];
  arr.forEach((item: any) => {
    const id = Number(item?.id); const ar = clean(String(item?.ar || ""));
    if (ids.includes(id) && ar) current.set(id, ar);
  });
  trace.push({ task: "review", provider: `groq:${GROQ_REVIEW_MODEL}`, latency_ms: Date.now() - started, ok: true });
  return segments.map((s) => ({ id: s.id, ar: current.get(s.id) || "" }));
}

async function analyzeTranscript(segments: Segment[], subtitles: Subtitle[], trace: ProviderTrace[]): Promise<any> {
  const key = Deno.env.get("GROQ_API_KEY");
  if (!key) return null;
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  const transcript = segments.map((s) => `${clock(s.start_ms)} ${s.text}\nAR: ${map.get(s.id) || ""}`).join("\n").slice(0, 55_000);
  const prompt = `Analyze this movie transcript. Return compact Arabic JSON only with keys: summary, characters (array of {name,role}), major_events (array), chapters (array of {title,start,summary}), keywords (array). Do not invent facts not supported by transcript.\n${transcript}`;
  const started = Date.now();
  const root = await groqJson(GROQ_FAST_MODEL, prompt, key);
  trace.push({ task: "analysis", provider: `groq:${GROQ_FAST_MODEL}`, latency_ms: Date.now() - started, ok: true });
  return root;
}

async function groqJson(model: string, prompt: string, key: string): Promise<any> {
  const response = await fetch(GROQ_CHAT_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      model,
      temperature: 0.1,
      response_format: { type: "json_object" },
      messages: [{ role: "user", content: prompt }],
    }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Groq LLM ${response.status}: ${text.slice(0, 300)}`);
  const body = JSON.parse(text);
  const content = String(body?.choices?.[0]?.message?.content || "{}");
  return JSON.parse(content);
}

function makeBatches(items: Segment[], maxItems: number, maxChars: number): Segment[][] {
  const out: Segment[][] = []; let batch: Segment[] = []; let chars = 0;
  for (const item of items) {
    const next = item.text.length + 32;
    if (batch.length && (batch.length >= maxItems || chars + next > maxChars)) { out.push(batch); batch = []; chars = 0; }
    batch.push(item); chars += next;
  }
  if (batch.length) out.push(batch);
  return out;
}

function toSrt(segments: Segment[], subtitles: Subtitle[]): string {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return segments.map((s, i) => `${i + 1}\n${srtTime(s.start_ms)} --> ${srtTime(s.end_ms)}\n${map.get(s.id) || ""}\n`).join("\n");
}

function toVtt(segments: Segment[], subtitles: Subtitle[]): string {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return `WEBVTT\n\n${segments.map((s) => `${vttTime(s.start_ms)} --> ${vttTime(s.end_ms)}\n${map.get(s.id) || ""}`).join("\n\n")}\n`;
}

function srtTime(ms: number): string { const h=Math.floor(ms/3600000); const m=Math.floor(ms%3600000/60000); const s=Math.floor(ms%60000/1000); const z=ms%1000; return `${pad(h)}:${pad(m)}:${pad(s)},${String(z).padStart(3,"0")}`; }
function vttTime(ms: number): string { return srtTime(ms).replace(",", "."); }
function clock(ms: number): string { return `${pad(Math.floor(ms/3600000))}:${pad(Math.floor(ms%3600000/60000))}:${pad(Math.floor(ms%60000/1000))}`; }
function pad(n: number): string { return String(n).padStart(2, "0"); }
function clean(v: string): string { return v.replace(/\s+/g, " ").trim(); }
function sanitizeClientId(v: unknown): string { const x=String(v || "anonymous").replace(/[^a-zA-Z0-9._-]/g, "").slice(0,80); return x || "anonymous"; }
function sanitizeTitle(v: unknown): string { return clean(String(v || "Movie")).slice(0,180) || "Movie"; }
function titleFromUrl(value: string): string { try { const p=new URL(value).pathname.split("/").filter(Boolean).pop() || "Movie"; return decodeURIComponent(p).slice(0,180); } catch { return "Movie"; } }

async function upsertJob(id: string, values: Record<string, unknown>) { await dbWrite("POST", "media_jobs?on_conflict=id", [{ id, ...values }], "resolution=merge-duplicates"); }
async function patchJob(id: string, values: Record<string, unknown>) { await dbWrite("PATCH", `media_jobs?id=eq.${encodeURIComponent(id)}`, { ...values, updated_at: new Date().toISOString() }); }

async function dbSelect(path: string): Promise<any> {
  const auth = serverAuth(); if (!auth) return [];
  const response = await fetch(`${auth.url}/rest/v1/${path}`, { headers: { apikey: auth.key, Authorization: `Bearer ${auth.key}` } });
  if (!response.ok) return [];
  return response.json();
}

async function dbWrite(method: string, path: string, body: unknown, prefer?: string): Promise<void> {
  const auth = serverAuth(); if (!auth) return;
  const response = await fetch(`${auth.url}/rest/v1/${path}`, {
    method,
    headers: { apikey: auth.key, Authorization: `Bearer ${auth.key}`, "Content-Type": "application/json", ...(prefer ? { Prefer: prefer } : {}) },
    body: JSON.stringify(body),
  });
  if (!response.ok) console.warn("db write skipped", response.status, (await response.text()).slice(0,200));
}

function serverAuth(): { url: string; key: string } | null {
  const url = Deno.env.get("SUPABASE_URL") || "";
  let key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  if (!key) {
    try { const all = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}"); key = String(all.default || Object.values(all)[0] || ""); } catch { /* noop */ }
  }
  return url && key ? { url, key } : null;
}

async function recordTrace(trace: ProviderTrace[], language: string, durationMs: number): Promise<void> {
  const bucket = durationBucket(durationMs);
  for (const item of trace) {
    // Append-only telemetry via RPC is ideal later; v1 keeps the latest sample safely.
    await dbWrite("POST", "provider_route_stats?on_conflict=provider,task,source_language,duration_bucket", [{
      provider: item.provider,
      task: item.task,
      source_language: language || "auto",
      duration_bucket: bucket,
      samples: 1,
      successes: item.ok ? 1 : 0,
      failures: item.ok ? 0 : 1,
      avg_latency_ms: item.latency_ms,
      quality_score: item.ok ? 0.9 : 0.5,
      last_error: item.ok ? null : "provider_error",
      last_used_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    }], "resolution=merge-duplicates");
  }
}

function json(body: unknown, status = 200): Response { return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders(), "Content-Type": "application/json; charset=utf-8" } }); }
function corsHeaders(): Record<string,string> { return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" }; }
