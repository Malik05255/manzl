export type Retention = "none" | "1d" | "7d" | "30d" | "permanent";
export type Segment = { id: number; start_ms: number; end_ms: number; text: string };
export type Subtitle = { id: number; ar: string };
export type Trace = { task: string; provider: string; latency_ms: number; ok: boolean; quality?: number; error?: string };

export type PipelineEnv = {
  MOVIES: R2Bucket;
  GROQ_API_KEY: string;
  AZURE_TRANSLATOR_KEY?: string;
  AZURE_TRANSLATOR_REGION?: string;
  AZURE_TRANSLATOR_ENDPOINT?: string;
  SUPABASE_URL: string;
  SUPABASE_SERVICE_ROLE_KEY: string;
  PUBLIC_BASE_URL: string;
};

const GROQ_CHAT = "https://api.groq.com/openai/v1/chat/completions";
const TRANSLATE_MODEL = "openai/gpt-oss-20b";
const REVIEW_MODEL = "openai/gpt-oss-120b";
const ANALYSIS_MODEL = "openai/gpt-oss-20b";

export function normalizeAsrSegments(input: unknown): Segment[] {
  const source = Array.isArray(input) ? input : [];
  return source.map((item: any, index) => {
    const hasMs = Number.isFinite(Number(item?.start_ms)) || Number.isFinite(Number(item?.end_ms));
    const start = hasMs ? Number(item?.start_ms || 0) : Number(item?.start || 0) * 1000;
    const end = hasMs ? Number(item?.end_ms || start + 1) : Number(item?.end || 0) * 1000;
    const startMs = Math.max(0, Math.round(start));
    const endMs = Math.max(startMs + 1, Math.round(end));
    return {
      id: index,
      start_ms: startMs,
      end_ms: endMs,
      text: clean(String(item?.text || item?.tr || "")),
    };
  }).filter((item: Segment) => item.text.length > 0);
}

export async function translateArabic(
  env: PipelineEnv,
  segments: Segment[],
  language: string,
  trace: Trace[],
): Promise<Subtitle[]> {
  // Free-first: Groq handles the bulk. Azure is reserved as a reliability fallback.
  let subtitles = await translateGroq(env, segments, language, trace).catch(() => [] as Subtitle[]);
  if (subtitles.length !== segments.length) {
    subtitles = await translateAzure(env, segments, language, trace).catch(() => [] as Subtitle[]);
  }
  if (subtitles.length !== segments.length) {
    throw new Error(`لم ترجع منصات الترجمة جميع الأسطر (${subtitles.length}/${segments.length}).`);
  }
  return subtitles;
}

export async function reviewSuspicious(
  env: PipelineEnv,
  segments: Segment[],
  subtitles: Subtitle[],
  trace: Trace[],
): Promise<{ subtitles: Subtitle[]; reviewed: number }> {
  const ids = suspiciousIds(segments, subtitles);
  if (!ids.length) return { subtitles, reviewed: 0 };
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  const context = segments.filter((s) => ids.includes(s.id) || ids.includes(s.id - 1) || ids.includes(s.id + 1));
  const prompt = `Review only target_ids for source fidelity and natural cinematic Arabic. Keep names and meaning consistent with neighboring lines. Never merge or delete ids. Return JSON only {"subtitles":[{"id":0,"ar":"..."}]}.\ntarget_ids=${JSON.stringify(ids)}\nsource=${JSON.stringify(context)}\ndraft=${JSON.stringify(context.map((s) => ({ id:s.id, ar:map.get(s.id)||"" })))}`;
  const started = Date.now();
  try {
    const root = await groqJson(env, REVIEW_MODEL, prompt);
    for (const item of Array.isArray(root?.subtitles) ? root.subtitles : []) {
      const id = Number(item?.id);
      const ar = clean(String(item?.ar || ""));
      if (ids.includes(id) && ar) map.set(id, ar);
    }
    trace.push({ task: "review", provider: `groq:${REVIEW_MODEL}`, latency_ms: Date.now() - started, ok: true, quality: 0.96 });
    return { subtitles: segments.map((s) => ({ id: s.id, ar: map.get(s.id) || "" })), reviewed: ids.length };
  } catch (error) {
    trace.push({ task: "review", provider: `groq:${REVIEW_MODEL}`, latency_ms: Date.now() - started, ok: false, quality: 0.6, error: shortError(error) });
    return { subtitles, reviewed: 0 };
  }
}

export async function analyzeMovie(
  env: PipelineEnv,
  segments: Segment[],
  subtitles: Subtitle[],
  trace: Trace[],
): Promise<any | null> {
  const ar = new Map(subtitles.map((x) => [x.id, x.ar]));
  const transcript = segments
    .map((s) => `${clock(s.start_ms)} ${s.text}\nAR: ${ar.get(s.id) || ""}`)
    .join("\n")
    .slice(0, 60_000);
  const prompt = `Analyze this movie transcript only from supported evidence. Return compact Arabic JSON with keys summary, characters [{name,role}], major_events [], chapters [{title,start,summary}], keywords []. Do not invent facts.\n${transcript}`;
  const started = Date.now();
  try {
    const root = await groqJson(env, ANALYSIS_MODEL, prompt);
    trace.push({ task: "analysis", provider: `groq:${ANALYSIS_MODEL}`, latency_ms: Date.now() - started, ok: true, quality: 0.90 });
    return root;
  } catch (error) {
    trace.push({ task: "analysis", provider: `groq:${ANALYSIS_MODEL}`, latency_ms: Date.now() - started, ok: false, quality: 0.5, error: shortError(error) });
    return null;
  }
}

export function toSrt(segments: Segment[], subtitles: Subtitle[]): string {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return segments.map((s, i) => `${i + 1}\n${srt(s.start_ms)} --> ${srt(s.end_ms)}\n${map.get(s.id) || ""}\n`).join("\n");
}

export function toVtt(segments: Segment[], subtitles: Subtitle[]): string {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return `WEBVTT\n\n${segments.map((s) => `${srt(s.start_ms).replace(",", ".")} --> ${srt(s.end_ms).replace(",", ".")}\n${map.get(s.id) || ""}`).join("\n\n")}\n`;
}

export async function storeRemoteMovie(
  env: PipelineEnv,
  sourceUrl: string,
  title: string,
  retention: Retention,
): Promise<{ key: string; playback_url: string }> {
  const response = await fetch(sourceUrl, { redirect: "follow" });
  if (!response.ok || !response.body) throw new Error(`Source fetch failed ${response.status}`);
  const length = Number(response.headers.get("content-length") || 0);
  if (length > 4_800_000_000) {
    throw new Error("النسخة المحفوظة أكبر من مسار R2 الحالي. استخدم بدون حفظ أو ملفًا أصغر.");
  }
  const filename = safeFilename(titleFromUrl(sourceUrl) || title || "movie.mp4");
  const key = `${retentionPrefix(retention)}/${crypto.randomUUID()}/${filename}`;
  const expiresAt = expiryFor(retention);
  await env.MOVIES.put(key, response.body, {
    httpMetadata: { contentType: response.headers.get("content-type") || "application/octet-stream" },
    customMetadata: {
      retention,
      created_at: new Date().toISOString(),
      ...(expiresAt ? { expires_at: expiresAt } : {}),
    },
  });
  const base = (env.PUBLIC_BASE_URL || "").replace(/\/$/, "");
  return { key, playback_url: base ? `${base}/media/${encodeURIComponent(key)}` : sourceUrl };
}

export async function getJob(env: PipelineEnv, id: string): Promise<any> {
  const rows = await restGet(env, `media_jobs?id=eq.${encodeURIComponent(id)}&limit=1`);
  if (!Array.isArray(rows) || !rows.length) throw new Error("مهمة الترجمة غير موجودة في قاعدة البيانات.");
  return rows[0];
}

export async function patchJob(env: PipelineEnv, id: string, values: Record<string, unknown>): Promise<void> {
  const response = await fetch(`${env.SUPABASE_URL.replace(/\/$/, "")}/rest/v1/media_jobs?id=eq.${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: {
      apikey: env.SUPABASE_SERVICE_ROLE_KEY,
      Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ ...values, updated_at: new Date().toISOString() }),
  });
  if (!response.ok) throw new Error(`Supabase checkpoint ${response.status}: ${(await response.text()).slice(0, 220)}`);
}

export async function getProviderStats(env: PipelineEnv, language: string, bucket: string): Promise<any[]> {
  const rows = await restGet(
    env,
    `provider_route_stats?task=eq.asr&source_language=eq.${encodeURIComponent(language || "auto")}&duration_bucket=eq.${encodeURIComponent(bucket)}&select=provider,samples,successes,failures,avg_latency_ms,quality_score`,
  );
  return Array.isArray(rows) ? rows : [];
}

export async function recordTrace(env: PipelineEnv, trace: Trace[], language: string, bucket: string): Promise<void> {
  for (const item of trace) {
    await fetch(`${env.SUPABASE_URL.replace(/\/$/, "")}/rest/v1/rpc/record_provider_route_sample`, {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        p_provider: item.provider,
        p_task: item.task,
        p_source_language: language || "auto",
        p_duration_bucket: bucket,
        p_ok: item.ok,
        p_latency_ms: item.latency_ms,
        p_quality_score: item.quality ?? null,
        p_error: item.error ?? null,
      }),
    }).catch(() => undefined);
  }
}

export function traceFrom(input: unknown): Trace[] {
  return Array.isArray(input)
    ? input.map((x: any) => ({
        task: String(x?.task || ""),
        provider: String(x?.provider || ""),
        latency_ms: Math.max(0, Number(x?.latency_ms || 0)),
        ok: Boolean(x?.ok),
        quality: x?.quality == null ? undefined : Number(x.quality),
        error: x?.error == null ? undefined : String(x.error),
      })).filter((x: Trace) => x.task && x.provider)
    : [];
}

export function segmentsFrom(input: unknown): Segment[] {
  return Array.isArray(input)
    ? input.map((x: any, i: number) => ({
        id: Number.isInteger(Number(x?.id)) ? Number(x.id) : i,
        start_ms: Math.max(0, Number(x?.start_ms || 0)),
        end_ms: Math.max(1, Number(x?.end_ms || 1)),
        text: clean(String(x?.text || "")),
      })).filter((x: Segment) => x.text.length > 0)
    : [];
}

export function subtitlesFrom(input: unknown): Subtitle[] {
  return Array.isArray(input)
    ? dedupeSubtitles(input.map((x: any) => ({ id: Number(x?.id), ar: clean(String(x?.ar || "")) })).filter((x: Subtitle) => Number.isInteger(x.id) && x.ar))
    : [];
}

export function normalizeLanguage(v: unknown): string {
  const x = String(v || "auto").toLowerCase().trim();
  if (!x || x === "auto") return "auto";
  const iso = x.split(/[-_]/)[0];
  return /^[a-z]{2,3}$/.test(iso) ? iso : "auto";
}

export function normalizeRetention(v: unknown): Retention {
  const x = String(v || "none");
  return x === "1d" || x === "7d" || x === "30d" || x === "permanent" ? x : "none";
}

export function durationBucket(ms: number): string {
  if (!ms) return "unknown";
  if (ms <= 20 * 60_000) return "short";
  if (ms <= 90 * 60_000) return "medium";
  return "long";
}

export function expiryFor(v: Retention): string | null {
  const d = v === "1d" ? 1 : v === "7d" ? 7 : v === "30d" ? 30 : 0;
  return d ? new Date(Date.now() + d * 86_400_000).toISOString() : null;
}

export function safeUrl(v: unknown): boolean {
  try {
    const u = new URL(String(v || ""));
    if (u.protocol !== "https:" && u.protocol !== "http:") return false;
    const h = u.hostname.toLowerCase();
    if (!h || h === "localhost" || h.endsWith(".local")) return false;
    if (/^(127\.|10\.|192\.168\.|169\.254\.)/.test(h)) return false;
    if (/^172\.(1[6-9]|2\d|3[01])\./.test(h)) return false;
    if (h === "0.0.0.0" || h === "::1") return false;
    return true;
  } catch {
    return false;
  }
}

export function titleFromUrl(v: string): string {
  try {
    return decodeURIComponent(new URL(v).pathname.split("/").filter(Boolean).pop() || "movie.mp4");
  } catch {
    return "movie.mp4";
  }
}

export function clean(v: string): string { return v.replace(/\s+/g, " ").trim(); }

function suspiciousIds(segments: Segment[], subtitles: Subtitle[]): number[] {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return segments.filter((s) => {
    const ar = map.get(s.id) || "";
    if (!ar) return true;
    if (ar.length > Math.max(180, s.text.length * 4)) return true;
    const latin = (ar.match(/[A-Za-z]/g) || []).length;
    return latin > Math.max(6, Math.round(ar.length * 0.30));
  }).map((s) => s.id).slice(0, 100);
}

async function translateGroq(env: PipelineEnv, segments: Segment[], language: string, trace: Trace[]): Promise<Subtitle[]> {
  const output: Subtitle[] = [];
  const started = Date.now();
  try {
    for (const batch of batches(segments, 60, 8_000)) {
      const prompt = `Translate movie subtitles from ${language === "auto" ? "the detected source language" : language} to natural Modern Standard Arabic. Preserve every id exactly once. Keep names consistent. Do not omit, merge, explain, or censor ordinary dialogue. Return JSON only {"subtitles":[{"id":0,"ar":"..."}]}.\n${JSON.stringify(batch.map((x) => ({ id: x.id, text: x.text })))}`;
      const root = await groqJson(env, TRANSLATE_MODEL, prompt);
      for (const item of Array.isArray(root?.subtitles) ? root.subtitles : []) {
        const id = Number(item?.id);
        const ar = clean(String(item?.ar || ""));
        if (Number.isInteger(id) && ar) output.push({ id, ar });
      }
    }
    const sorted = dedupeSubtitles(output).sort((a, b) => a.id - b.id);
    trace.push({ task: "translate", provider: `groq:${TRANSLATE_MODEL}`, latency_ms: Date.now() - started, ok: sorted.length === segments.length, quality: sorted.length === segments.length ? 0.91 : 0.60 });
    return sorted;
  } catch (error) {
    trace.push({ task: "translate", provider: `groq:${TRANSLATE_MODEL}`, latency_ms: Date.now() - started, ok: false, quality: 0.5, error: shortError(error) });
    throw error;
  }
}

async function translateAzure(env: PipelineEnv, segments: Segment[], language: string, trace: Trace[]): Promise<Subtitle[]> {
  if (!env.AZURE_TRANSLATOR_KEY || !env.AZURE_TRANSLATOR_REGION) throw new Error("Azure not configured");
  const endpoint = (env.AZURE_TRANSLATOR_ENDPOINT || "https://api.cognitive.microsofttranslator.com").replace(/\/$/, "");
  const from = language !== "auto" ? `&from=${encodeURIComponent(language)}` : "";
  const output: Subtitle[] = [];
  const started = Date.now();
  try {
    for (const batch of batches(segments, 80, 40_000)) {
      const response = await fetch(`${endpoint}/translate?api-version=3.0${from}&to=ar`, {
        method: "POST",
        headers: {
          "content-type": "application/json; charset=UTF-8",
          "Ocp-Apim-Subscription-Key": env.AZURE_TRANSLATOR_KEY,
          "Ocp-Apim-Subscription-Region": env.AZURE_TRANSLATOR_REGION,
        },
        body: JSON.stringify(batch.map((x) => ({ Text: x.text }))),
      });
      const text = await response.text();
      if (!response.ok) throw new Error(`Azure ${response.status}: ${text.slice(0, 240)}`);
      const root = JSON.parse(text);
      if (!Array.isArray(root) || root.length !== batch.length) throw new Error("Azure result mismatch");
      batch.forEach((x, i) => {
        const ar = clean(String(root[i]?.translations?.[0]?.text || ""));
        if (ar) output.push({ id: x.id, ar });
      });
    }
    trace.push({ task: "translate", provider: "azure-translator", latency_ms: Date.now() - started, ok: output.length === segments.length, quality: output.length === segments.length ? 0.92 : 0.60 });
    return output;
  } catch (error) {
    trace.push({ task: "translate", provider: "azure-translator", latency_ms: Date.now() - started, ok: false, quality: 0.5, error: shortError(error) });
    throw error;
  }
}

async function groqJson(env: PipelineEnv, model: string, prompt: string): Promise<any> {
  const response = await fetch(GROQ_CHAT, {
    method: "POST",
    headers: { Authorization: `Bearer ${env.GROQ_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({
      model,
      temperature: 0.1,
      response_format: { type: "json_object" },
      messages: [{ role: "user", content: prompt }],
    }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Groq LLM ${response.status}: ${text.slice(0, 240)}`);
  const root = JSON.parse(text);
  return JSON.parse(String(root?.choices?.[0]?.message?.content || "{}"));
}

async function restGet(env: PipelineEnv, path: string): Promise<any> {
  const response = await fetch(`${env.SUPABASE_URL.replace(/\/$/, "")}/rest/v1/${path}`, {
    headers: {
      apikey: env.SUPABASE_SERVICE_ROLE_KEY,
      Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
    },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Supabase read ${response.status}: ${text.slice(0, 200)}`);
  return JSON.parse(text || "[]");
}

function batches(items: Segment[], maxItems: number, maxChars: number): Segment[][] {
  const out: Segment[][] = [];
  let current: Segment[] = [];
  let chars = 0;
  for (const item of items) {
    const size = item.text.length + 32;
    if (current.length && (current.length >= maxItems || chars + size > maxChars)) {
      out.push(current);
      current = [];
      chars = 0;
    }
    current.push(item);
    chars += size;
  }
  if (current.length) out.push(current);
  return out;
}

function dedupeSubtitles(items: Subtitle[]): Subtitle[] {
  const map = new Map<number, string>();
  for (const item of items) if (Number.isInteger(item.id) && item.ar) map.set(item.id, item.ar);
  return [...map.entries()].map(([id, ar]) => ({ id, ar }));
}

function retentionPrefix(v: Retention): string {
  return v === "1d" ? "ttl/1d" : v === "30d" ? "ttl/30d" : v === "permanent" ? "permanent" : "ttl/7d";
}
function safeFilename(v: string): string { return v.replace(/[\\/:*?"<>|]+/g, "_").slice(0, 150) || "movie.mp4"; }
function successError(error: unknown): string { return error instanceof Error ? error.message : String(error || "provider_error"); }
function shortError(error: unknown): string { return successError(error).slice(0, 180); }
function pad(n: number): string { return String(n).padStart(2, "0"); }
function clock(ms: number): string { return `${pad(Math.floor(ms / 3600000))}:${pad(Math.floor(ms % 3600000 / 60000))}:${pad(Math.floor(ms % 60000 / 1000))}`; }
function srt(ms: number): string {
  const h = Math.floor(ms / 3600000), m = Math.floor(ms % 3600000 / 60000), s = Math.floor(ms % 60000 / 1000), z = Math.floor(ms % 1000);
  return `${pad(h)}:${pad(m)}:${pad(s)},${String(z).padStart(3, "0")}`;
}
