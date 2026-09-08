import { WorkflowEntrypoint, WorkflowEvent, WorkflowStep } from "cloudflare:workers";

interface Env {
  SMART_MEDIA_WORKFLOW: Workflow;
  MOVIES: R2Bucket;
  GROQ_API_KEY: string;
  AZURE_TRANSLATOR_KEY?: string;
  AZURE_TRANSLATOR_REGION?: string;
  AZURE_TRANSLATOR_ENDPOINT?: string;
  SUPABASE_URL: string;
  SUPABASE_SERVICE_ROLE_KEY: string;
  ORCHESTRATOR_TOKEN: string;
  PUBLIC_BASE_URL: string;
}

type Retention = "none" | "1d" | "7d" | "30d" | "permanent";
type Segment = { id: number; start_ms: number; end_ms: number; text: string };
type Subtitle = { id: number; ar: string };
type Trace = { task: string; provider: string; latency_ms: number; ok: boolean; quality?: number; error?: string };
type JobPayload = {
  job_id: string;
  source_url: string;
  title: string;
  retention: Retention;
  language: string;
  duration_ms: number;
};

type RoutePlan = {
  language: string;
  bucket: string;
  primary: string;
  fallback: string;
  reason: string;
};

const GROQ_TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions";
const GROQ_CHAT = "https://api.groq.com/openai/v1/chat/completions";
const LARGE = "whisper-large-v3";
const TURBO = "whisper-large-v3-turbo";
const TRANSLATE_MODEL = "openai/gpt-oss-20b";
const REVIEW_MODEL = "openai/gpt-oss-120b";
const ANALYSIS_MODEL = "openai/gpt-oss-20b";

export class SmartMediaWorkflow extends WorkflowEntrypoint<Env, JobPayload> {
  async run(event: WorkflowEvent<JobPayload>, step: WorkflowStep) {
    const job = event.payload;

    try {
      const plan = await step.do("choose quality route", async () => chooseRoute(this.env, job));

      await step.do("mark started", async () => {
        await patchJob(this.env, job.job_id, {
          status: "processing",
          progress: 0.05,
          stage: "بدء المعالجة السحابية",
          started_at: new Date().toISOString(),
          error: null,
        });
        return { ok: true };
      });

      const retained = await step.do("prepare playback source", async () => {
        if (job.retention === "none") {
          await patchJob(this.env, job.job_id, {
            playback_url: job.source_url,
            storage_provider: null,
            progress: 0.08,
            stage: "فهم صوت الفيلم",
          });
          return { playback_url: job.source_url, storage_provider: null as string | null };
        }

        const stored = await storeRemoteMovie(this.env, job.source_url, job.title, job.retention);
        await patchJob(this.env, job.job_id, {
          playback_url: stored.playback_url,
          storage_provider: "cloudflare-r2",
          expires_at: expiryFor(job.retention),
          progress: 0.08,
          stage: "فهم صوت الفيلم",
        });
        return { playback_url: stored.playback_url, storage_provider: "cloudflare-r2" };
      });

      const asrCheckpoint = await step.do("transcribe and checkpoint", async () => {
        const row = await getJob(this.env, job.job_id);
        const trace = traceFrom(row.provider_trace);
        let asr: any;
        try {
          asr = await transcribe(this.env, job.source_url, plan.primary, plan.language, trace);
        } catch {
          asr = await transcribe(this.env, job.source_url, plan.fallback, plan.language, trace);
        }
        const detected = normalizeLanguage(asr?.language || plan.language);
        const segments = normalizeSegments(asr?.segments);
        if (!segments.length) throw new Error("لم يتم العثور على حوار واضح في الفيلم.");

        await patchJob(this.env, job.job_id, {
          source_language: detected === "auto" ? null : detected,
          transcript: segments,
          provider_trace: trace,
          progress: 0.56,
          stage: "ترجمة الحوار للعربية",
        });
        return { detected, segment_count: segments.length };
      });

      await step.do("translate and checkpoint", async () => {
        const row = await getJob(this.env, job.job_id);
        const segments = segmentsFrom(row.transcript);
        const trace = traceFrom(row.provider_trace);
        if (!segments.length) throw new Error("فقدت بيانات الحوار قبل الترجمة.");

        // Free-first bulk translation. Azure is reserved as a reliability fallback so its monthly quota lasts longer.
        let subtitles = await translateGroq(this.env, segments, asrCheckpoint.detected, trace).catch(() => [] as Subtitle[]);
        if (subtitles.length !== segments.length) {
          subtitles = await translateAzure(this.env, segments, asrCheckpoint.detected, trace);
        }
        if (subtitles.length !== segments.length) throw new Error("لم ترجع منصة الترجمة جميع الأسطر.");

        await patchJob(this.env, job.job_id, {
          subtitles,
          provider_trace: trace,
          progress: 0.80,
          stage: "مراجعة جودة الترجمة",
        });
        return { translated_count: subtitles.length };
      });

      await step.do("review suspicious lines", async () => {
        const row = await getJob(this.env, job.job_id);
        const segments = segmentsFrom(row.transcript);
        const subtitles = subtitlesFrom(row.subtitles);
        const trace = traceFrom(row.provider_trace);
        const ids = suspiciousIds(segments, subtitles);
        if (!ids.length) {
          await patchJob(this.env, job.job_id, { progress: 0.86, stage: "إنشاء ملفات الترجمة" });
          return { reviewed_count: 0 };
        }

        const reviewed = await reviewSelected(this.env, segments, subtitles, ids, trace).catch(() => subtitles);
        await patchJob(this.env, job.job_id, {
          subtitles: reviewed,
          provider_trace: trace,
          progress: 0.86,
          stage: "إنشاء ملفات الترجمة",
        });
        return { reviewed_count: ids.length };
      });

      await step.do("build subtitle files", async () => {
        const row = await getJob(this.env, job.job_id);
        const segments = segmentsFrom(row.transcript);
        const subtitles = subtitlesFrom(row.subtitles);
        if (!segments.length || subtitles.length !== segments.length) throw new Error("بيانات الترجمة غير مكتملة.");
        const srtText = toSrt(segments, subtitles);
        const vttText = toVtt(segments, subtitles);
        await patchJob(this.env, job.job_id, {
          srt_text: srtText,
          vtt_text: vttText,
          progress: 0.91,
          stage: "إنشاء الملخص والتحليل",
        });
        return { srt_bytes: srtText.length, vtt_bytes: vttText.length };
      });

      await step.do("analyze and checkpoint", async () => {
        const row = await getJob(this.env, job.job_id);
        const segments = segmentsFrom(row.transcript);
        const subtitles = subtitlesFrom(row.subtitles);
        const trace = traceFrom(row.provider_trace);
        const summary = await analyzeTranscript(this.env, segments, subtitles, trace).catch(() => null);
        await patchJob(this.env, job.job_id, {
          summary,
          provider_trace: trace,
          progress: 0.97,
          stage: "حفظ النتائج",
        });
        return { summary_ready: summary != null };
      });

      await step.do("learn provider performance", async () => {
        const row = await getJob(this.env, job.job_id);
        await recordTrace(this.env, traceFrom(row.provider_trace), row.source_language || plan.language, plan.bucket);
        return { samples_recorded: traceFrom(row.provider_trace).length };
      });

      await step.do("complete job", async () => {
        await patchJob(this.env, job.job_id, {
          status: "completed",
          progress: 1,
          stage: "جاهز للمشاهدة",
          playback_url: retained.playback_url,
          completed_at: new Date().toISOString(),
          error: null,
        });
        return { ok: true };
      });

      // Keep Workflow state tiny. Full transcript/subtitles/results live in Supabase.
      return {
        job_id: job.job_id,
        status: "completed",
        playback_url: retained.playback_url,
        language: asrCheckpoint.detected,
      };
    } catch (error) {
      const message = error instanceof Error ? error.message : "تعذر إكمال المعالجة السحابية.";
      await step.do("mark failed", async () => {
        await patchJob(this.env, job.job_id, {
          status: "failed",
          stage: "تعذر إكمال المهمة",
          error: message.slice(0, 1000),
        }).catch(() => undefined);
        return { failed: true };
      });
      throw error;
    }
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response("ok", { headers: cors() });
    if (url.pathname === "/health") return json({ ok: true, service: "h-ai-smart-media-orchestrator" });

    if (request.method === "POST" && url.pathname === "/jobs") {
      if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);
      const body = await request.json<any>();
      if (!safeUrl(body?.source_url)) return json({ error: "invalid_source_url" }, 400);
      const id = String(body?.job_id || crypto.randomUUID()).slice(0, 100);
      const params: JobPayload = {
        job_id: id,
        source_url: String(body.source_url),
        title: clean(String(body?.title || titleFromUrl(String(body.source_url)))).slice(0, 180),
        retention: normalizeRetention(body?.retention),
        language: normalizeLanguage(body?.language),
        duration_ms: Math.max(0, Number(body?.duration_ms || 0)),
      };
      const instance = await env.SMART_MEDIA_WORKFLOW.create({ id, params });
      return json({ status: "accepted", job_id: instance.id, workflow: await instance.status() }, 202);
    }

    if (request.method === "GET" && url.pathname.startsWith("/jobs/")) {
      if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);
      const id = decodeURIComponent(url.pathname.slice(6));
      const instance = await env.SMART_MEDIA_WORKFLOW.get(id);
      return json({ job_id: id, workflow: await instance.status() });
    }

    if (request.method === "GET" && url.pathname.startsWith("/media/")) {
      const key = decodeURIComponent(url.pathname.slice(7));
      const object = await env.MOVIES.get(key, { range: request.headers.get("range") || undefined });
      if (!object || !object.body) return new Response("Not found", { status: 404, headers: cors() });
      const headers = new Headers(cors());
      object.writeHttpMetadata(headers);
      headers.set("etag", object.httpEtag);
      headers.set("accept-ranges", "bytes");
      headers.set("cache-control", "private, max-age=300");
      if (object.range && "offset" in object.range && "length" in object.range) {
        const start = object.range.offset;
        const end = start + object.range.length - 1;
        headers.set("content-range", `bytes ${start}-${end}/${object.size}`);
        headers.set("content-length", String(object.range.length));
        return new Response(object.body, { status: 206, headers });
      }
      headers.set("content-length", String(object.size));
      return new Response(object.body, { headers });
    }

    return json({ error: "not_found" }, 404);
  },
} satisfies ExportedHandler<Env>;

async function chooseRoute(env: Env, job: JobPayload): Promise<RoutePlan> {
  const language = normalizeLanguage(job.language);
  const bucket = durationBucket(job.duration_ms || 2 * 60 * 60_000);
  const complexLanguage = ["ja", "hi", "ko", "zh"].includes(language);
  const qualityHardGate = language === "auto" || complexLanguage || bucket === "long";

  if (qualityHardGate) {
    // Long/unknown audio starts on V3 for quality unless accumulated data later proves Turbo equally reliable.
    const stats = await getProviderStats(env, language, bucket).catch(() => [] as any[]);
    const turbo = stats.find((x) => x.provider === `groq:${TURBO}`);
    const v3 = stats.find((x) => x.provider === `groq:${LARGE}`);
    if (
      language !== "auto" && !complexLanguage &&
      turbo && Number(turbo.samples || 0) >= 5 &&
      successRate(turbo) >= 0.985 && Number(turbo.quality_score || 0) >= 0.90 &&
      (!v3 || Number(turbo.avg_latency_ms || Infinity) < Number(v3.avg_latency_ms || Infinity) * 0.85)
    ) {
      return { language, bucket, primary: TURBO, fallback: LARGE, reason: "learned-turbo-high-confidence" };
    }
    return { language, bucket, primary: LARGE, fallback: TURBO, reason: "quality-hard-gate" };
  }

  const stats = await getProviderStats(env, language, bucket).catch(() => [] as any[]);
  const turbo = stats.find((x) => x.provider === `groq:${TURBO}`);
  if (turbo && Number(turbo.samples || 0) >= 3 && successRate(turbo) < 0.95) {
    return { language, bucket, primary: LARGE, fallback: TURBO, reason: "learned-turbo-unreliable" };
  }
  return { language, bucket, primary: TURBO, fallback: LARGE, reason: "fast-known-short" };
}

async function transcribe(env: Env, sourceUrl: string, model: string, language: string, trace: Trace[]) {
  const form = new FormData();
  form.append("url", sourceUrl);
  form.append("model", model);
  form.append("response_format", "verbose_json");
  form.append("temperature", "0");
  form.append("timestamp_granularities[]", "segment");
  if (language !== "auto") form.append("language", language);
  const started = Date.now();
  const response = await fetch(GROQ_TRANSCRIBE, {
    method: "POST",
    headers: { Authorization: `Bearer ${env.GROQ_API_KEY}` },
    body: form,
  });
  const text = await response.text();
  const item: Trace = {
    task: "asr",
    provider: `groq:${model}`,
    latency_ms: Date.now() - started,
    ok: response.ok,
    quality: response.ok ? (model === LARGE ? 0.95 : 0.90) : 0.45,
  };
  if (!response.ok) item.error = `http_${response.status}`;
  trace.push(item);
  if (!response.ok) throw new Error(`Groq ${response.status}: ${text.slice(0, 250)}`);
  return JSON.parse(text);
}

async function translateGroq(env: Env, segments: Segment[], language: string, trace: Trace[]): Promise<Subtitle[]> {
  const output: Subtitle[] = [];
  const started = Date.now();
  try {
    for (const batch of batches(segments, 60, 8_000)) {
      const prompt = `Translate movie subtitles from ${language === "auto" ? "the detected language" : language} to natural Modern Standard Arabic. Preserve every id exactly once. Keep names consistent. Return JSON only as {"subtitles":[{"id":0,"ar":"..."}]}. No explanations.\n${JSON.stringify(batch.map((x) => ({ id: x.id, text: x.text })))}`;
      const root = await groqJson(env, TRANSLATE_MODEL, prompt);
      for (const item of Array.isArray(root?.subtitles) ? root.subtitles : []) {
        const id = Number(item?.id);
        const ar = clean(String(item?.ar || ""));
        if (Number.isInteger(id) && ar) output.push({ id, ar });
      }
    }
    const sorted = dedupeSubtitles(output).sort((a, b) => a.id - b.id);
    trace.push({
      task: "translate",
      provider: `groq:${TRANSLATE_MODEL}`,
      latency_ms: Date.now() - started,
      ok: sorted.length === segments.length,
      quality: sorted.length === segments.length ? 0.90 : 0.60,
    });
    return sorted;
  } catch (error) {
    trace.push({ task: "translate", provider: `groq:${TRANSLATE_MODEL}`, latency_ms: Date.now() - started, ok: false, quality: 0.5, error: "provider_error" });
    throw error;
  }
}

async function translateAzure(env: Env, segments: Segment[], language: string, trace: Trace[]): Promise<Subtitle[]> {
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
      if (!response.ok) throw new Error(`Azure ${response.status}: ${text.slice(0, 250)}`);
      const root = JSON.parse(text);
      if (!Array.isArray(root) || root.length !== batch.length) throw new Error("Azure mismatch");
      batch.forEach((x, i) => output.push({ id: x.id, ar: clean(String(root[i]?.translations?.[0]?.text || "")) }));
    }
    trace.push({ task: "translate", provider: "azure-translator", latency_ms: Date.now() - started, ok: true, quality: 0.92 });
    return output;
  } catch (error) {
    trace.push({ task: "translate", provider: "azure-translator", latency_ms: Date.now() - started, ok: false, quality: 0.5, error: "provider_error" });
    throw error;
  }
}

async function reviewSelected(env: Env, segments: Segment[], subtitles: Subtitle[], ids: number[], trace: Trace[]): Promise<Subtitle[]> {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  const context = segments.filter((s) => ids.includes(s.id) || ids.includes(s.id - 1) || ids.includes(s.id + 1));
  const prompt = `Review only target_ids for fidelity and cinematic Arabic. Keep names and meaning consistent with neighboring lines. Return JSON only {"subtitles":[{"id":0,"ar":"..."}]}.\ntarget_ids=${JSON.stringify(ids)}\nsource=${JSON.stringify(context)}\ndraft=${JSON.stringify(context.map((s) => ({ id:s.id, ar:map.get(s.id)||"" })))}`;
  const started = Date.now();
  const root = await groqJson(env, REVIEW_MODEL, prompt);
  for (const item of Array.isArray(root?.subtitles) ? root.subtitles : []) {
    const id = Number(item?.id);
    const ar = clean(String(item?.ar || ""));
    if (ids.includes(id) && ar) map.set(id, ar);
  }
  trace.push({ task: "review", provider: `groq:${REVIEW_MODEL}`, latency_ms: Date.now() - started, ok: true, quality: 0.96 });
  return segments.map((s) => ({ id: s.id, ar: map.get(s.id) || "" }));
}

async function analyzeTranscript(env: Env, segments: Segment[], subtitles: Subtitle[], trace: Trace[]) {
  const ar = new Map(subtitles.map((x) => [x.id, x.ar]));
  const transcript = segments.map((s) => `${clock(s.start_ms)} ${s.text}\nAR: ${ar.get(s.id) || ""}`).join("\n").slice(0, 55_000);
  const prompt = `Analyze this movie transcript. Return compact Arabic JSON only with keys summary, characters [{name,role}], major_events [], chapters [{title,start,summary}], keywords []. Do not invent unsupported facts.\n${transcript}`;
  const started = Date.now();
  const root = await groqJson(env, ANALYSIS_MODEL, prompt);
  trace.push({ task: "analysis", provider: `groq:${ANALYSIS_MODEL}`, latency_ms: Date.now() - started, ok: true, quality: 0.90 });
  return root;
}

async function groqJson(env: Env, model: string, prompt: string) {
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
  if (!response.ok) throw new Error(`Groq LLM ${response.status}: ${text.slice(0, 250)}`);
  const root = JSON.parse(text);
  return JSON.parse(String(root?.choices?.[0]?.message?.content || "{}"));
}

async function storeRemoteMovie(env: Env, sourceUrl: string, title: string, retention: Retention) {
  const response = await fetch(sourceUrl);
  if (!response.ok || !response.body) throw new Error(`Source fetch failed ${response.status}`);
  const length = Number(response.headers.get("content-length") || 0);
  if (length > 4_800_000_000) throw new Error("النسخة المحفوظة أكبر من مسار R2 الحالي. استخدم بدون حفظ لهذا الفيلم.");
  const filename = safeFilename(titleFromUrl(sourceUrl) || title || "movie.mp4");
  const key = `${retentionPrefix(retention)}/${crypto.randomUUID()}/${filename}`;
  await env.MOVIES.put(key, response.body, {
    httpMetadata: { contentType: response.headers.get("content-type") || "application/octet-stream" },
    customMetadata: { retention, created_at: new Date().toISOString() },
  });
  const base = (env.PUBLIC_BASE_URL || "").replace(/\/$/, "");
  return { key, playback_url: base ? `${base}/media/${encodeURIComponent(key)}` : sourceUrl };
}

async function getJob(env: Env, id: string): Promise<any> {
  const rows = await restGet(env, `media_jobs?id=eq.${encodeURIComponent(id)}&limit=1`);
  if (!Array.isArray(rows) || !rows.length) throw new Error("مهمة الترجمة غير موجودة في قاعدة البيانات.");
  return rows[0];
}

async function getProviderStats(env: Env, language: string, bucket: string): Promise<any[]> {
  const rows = await restGet(
    env,
    `provider_route_stats?task=eq.asr&source_language=eq.${encodeURIComponent(language || "auto")}&duration_bucket=eq.${encodeURIComponent(bucket)}&select=provider,samples,successes,failures,avg_latency_ms,quality_score`,
  );
  return Array.isArray(rows) ? rows : [];
}

async function restGet(env: Env, path: string): Promise<any> {
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

async function patchJob(env: Env, id: string, values: Record<string, unknown>) {
  const response = await fetch(`${env.SUPABASE_URL.replace(/\/$/, "")}/rest/v1/media_jobs?id=eq.${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: {
      apikey: env.SUPABASE_SERVICE_ROLE_KEY,
      Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ ...values, updated_at: new Date().toISOString() }),
  });
  if (!response.ok) throw new Error(`Supabase checkpoint ${response.status}: ${(await response.text()).slice(0, 200)}`);
}

async function recordTrace(env: Env, trace: Trace[], language: string, bucket: string) {
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

function traceFrom(input: unknown): Trace[] {
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

function segmentsFrom(input: unknown): Segment[] {
  return Array.isArray(input)
    ? input.map((x: any, i: number) => ({
        id: Number.isInteger(Number(x?.id)) ? Number(x.id) : i,
        start_ms: Math.max(0, Number(x?.start_ms || 0)),
        end_ms: Math.max(1, Number(x?.end_ms || 1)),
        text: clean(String(x?.text || "")),
      })).filter((x: Segment) => x.text.length > 0)
    : [];
}

function subtitlesFrom(input: unknown): Subtitle[] {
  return Array.isArray(input)
    ? dedupeSubtitles(input.map((x: any) => ({ id: Number(x?.id), ar: clean(String(x?.ar || "")) })).filter((x: Subtitle) => Number.isInteger(x.id) && x.ar))
    : [];
}

function normalizeSegments(input: any): Segment[] {
  return (Array.isArray(input) ? input : []).map((x: any, i: number) => {
    const start = Math.max(0, Math.round(Number(x?.start || 0) * 1000));
    const end = Math.max(start + 1, Math.round(Number(x?.end || 0) * 1000));
    return { id: i, start_ms: start, end_ms: end, text: clean(String(x?.text || "")) };
  }).filter((x: Segment) => x.text.length > 0);
}

function suspiciousIds(segments: Segment[], subtitles: Subtitle[]) {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return segments.filter((s) => {
    const ar = map.get(s.id) || "";
    if (!ar) return true;
    if (ar.length > Math.max(180, s.text.length * 4)) return true;
    return (ar.match(/[A-Za-z]/g) || []).length > Math.max(6, Math.round(ar.length * 0.3));
  }).map((s) => s.id).slice(0, 80);
}

function batches(items: Segment[], maxItems: number, maxChars: number) {
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

function toSrt(segments: Segment[], subtitles: Subtitle[]) {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return segments.map((s, i) => `${i + 1}\n${srt(s.start_ms)} --> ${srt(s.end_ms)}\n${map.get(s.id) || ""}\n`).join("\n");
}

function toVtt(segments: Segment[], subtitles: Subtitle[]) {
  const map = new Map(subtitles.map((x) => [x.id, x.ar]));
  return `WEBVTT\n\n${segments.map((s) => `${srt(s.start_ms).replace(",", ".")} --> ${srt(s.end_ms).replace(",", ".")}\n${map.get(s.id) || ""}`).join("\n\n")}\n`;
}

function successRate(row: any): number {
  const samples = Math.max(0, Number(row?.samples || 0));
  return samples ? Math.max(0, Number(row?.successes || 0)) / samples : 0;
}

function srt(ms: number) {
  const h = Math.floor(ms / 3600000), m = Math.floor(ms % 3600000 / 60000), s = Math.floor(ms % 60000 / 1000), z = ms % 1000;
  return `${pad(h)}:${pad(m)}:${pad(s)},${String(z).padStart(3, "0")}`;
}
function clock(ms: number) { return `${pad(Math.floor(ms / 3600000))}:${pad(Math.floor(ms % 3600000 / 60000))}:${pad(Math.floor(ms % 60000 / 1000))}`; }
function pad(n: number) { return String(n).padStart(2, "0"); }
function clean(v: string) { return v.replace(/\s+/g, " ").trim(); }
function durationBucket(ms: number) { if (!ms) return "unknown"; if (ms <= 20 * 60000) return "short"; if (ms <= 90 * 60000) return "medium"; return "long"; }
function normalizeLanguage(v: unknown) { const x = String(v || "auto").toLowerCase().trim(); if (!x || x === "auto") return "auto"; const iso = x.split(/[-_]/)[0]; return /^[a-z]{2,3}$/.test(iso) ? iso : "auto"; }
function normalizeRetention(v: unknown): Retention { const x = String(v || "none"); return x === "1d" || x === "7d" || x === "30d" || x === "permanent" ? x : "none"; }
function retentionPrefix(v: Retention) { return v === "1d" ? "ttl/1d" : v === "30d" ? "ttl/30d" : v === "permanent" ? "permanent" : "ttl/7d"; }
function expiryFor(v: Retention) { const d = v === "1d" ? 1 : v === "7d" ? 7 : v === "30d" ? 30 : 0; return d ? new Date(Date.now() + d * 86400000).toISOString() : null; }
function titleFromUrl(v: string) { try { return decodeURIComponent(new URL(v).pathname.split("/").filter(Boolean).pop() || "movie.mp4"); } catch { return "movie.mp4"; } }
function safeFilename(v: string) { return v.replace(/[\\/:*?"<>|]+/g, "_").slice(0, 150) || "movie.mp4"; }
function safeUrl(v: unknown) { try { const u = new URL(String(v || "")); if (u.protocol !== "https:" && u.protocol !== "http:") return false; const h = u.hostname.toLowerCase(); if (!h || h === "localhost" || h.endsWith(".local")) return false; if (/^(127\.|10\.|192\.168\.|169\.254\.)/.test(h)) return false; if (/^172\.(1[6-9]|2\d|3[01])\./.test(h)) return false; return true; } catch { return false; } }
function authorized(req: Request, env: Env) { return !!env.ORCHESTRATOR_TOKEN && req.headers.get("x-orchestrator-token") === env.ORCHESTRATOR_TOKEN; }
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { ...cors(), "content-type": "application/json; charset=utf-8" } }); }
function cors() { return { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-headers": "content-type,x-orchestrator-token,range", "access-control-expose-headers": "content-range,content-length,etag,accept-ranges" }; }
