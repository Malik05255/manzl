import { WorkflowEntrypoint, WorkflowEvent, WorkflowStep } from "cloudflare:workers";
import { transcribeRemoteMedia, RemoteAsrEnv } from "./remote-asr";
import {
  PipelineEnv,
  Retention,
  Trace,
  analyzeMovie,
  durationBucket,
  expiryFor,
  getJob,
  getProviderStats,
  normalizeAsrSegments,
  normalizeLanguage,
  normalizeRetention,
  patchJob,
  recordTrace,
  reviewSuspicious,
  safeUrl,
  segmentsFrom,
  storeRemoteMovie,
  subtitlesFrom,
  titleFromUrl,
  toSrt,
  toVtt,
  traceFrom,
  translateArabic,
} from "./pipeline";

interface Env extends PipelineEnv, RemoteAsrEnv {
  SMART_MEDIA_WORKFLOW: Workflow;
  ORCHESTRATOR_TOKEN: string;
}

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

const LARGE = "whisper-large-v3";
const TURBO = "whisper-large-v3-turbo";
const VERSION = "2026-09-08-smart-media-v3";

export class SmartMediaWorkflow extends WorkflowEntrypoint<Env, JobPayload> {
  async run(event: WorkflowEvent<JobPayload>, step: WorkflowStep) {
    const job = event.payload;

    try {
      const plan = await step.do("choose quality route", async () => chooseRoute(this.env, job));

      await step.do("mark started", async () => {
        await patchJob(this.env, job.job_id, {
          status: "processing",
          progress: 0.03,
          stage: "فحص رابط الفيلم",
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
            progress: 0.06,
            stage: "اختيار مسار فهم الصوت",
          });
          return { playback_url: job.source_url, storage_provider: null as string | null };
        }

        const stored = await storeRemoteMovie(this.env, job.source_url, job.title, job.retention);
        await patchJob(this.env, job.job_id, {
          playback_url: stored.playback_url,
          storage_provider: "cloudflare-r2",
          expires_at: expiryFor(job.retention),
          progress: 0.08,
          stage: "اختيار مسار فهم الصوت",
        });
        return { playback_url: stored.playback_url, storage_provider: "cloudflare-r2" };
      });

      const asrCheckpoint = await step.do("transcribe remote media", async () => {
        const row = await getJob(this.env, job.job_id);
        const trace = traceFrom(row.provider_trace);
        const asrSource = retained.storage_provider === "cloudflare-r2" && retained.playback_url
          ? retained.playback_url
          : job.source_url;

        const asr = await transcribeRemoteMedia(
          this.env,
          {
            sourceUrl: asrSource,
            title: job.title,
            language: plan.language,
            primaryModel: plan.primary,
            fallbackModel: plan.fallback,
          },
          trace,
        );

        const detected = normalizeLanguage(asr.language || plan.language);
        const segments = normalizeAsrSegments(asr.segments);
        if (!segments.length) throw new Error("لم يتم العثور على حوار واضح في الفيلم.");

        await patchJob(this.env, job.job_id, {
          source_language: detected === "auto" ? null : detected,
          transcript: segments,
          provider_trace: trace,
          source_size_bytes: asr.source_size_bytes ?? null,
          source_mime: asr.source_mime ?? null,
          asr_route: asr.route,
          progress: 0.56,
          stage: "ترجمة الحوار للعربية",
        });
        return {
          detected,
          segment_count: segments.length,
          asr_route: asr.route,
          source_size_bytes: asr.source_size_bytes ?? null,
        };
      });

      await step.do("translate Arabic", async () => {
        const row = await getJob(this.env, job.job_id);
        const segments = segmentsFrom(row.transcript);
        const trace = traceFrom(row.provider_trace);
        if (!segments.length) throw new Error("فقدت بيانات الحوار قبل الترجمة.");
        const subtitles = await translateArabic(this.env, segments, asrCheckpoint.detected, trace);
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
        const reviewed = await reviewSuspicious(this.env, segments, subtitles, trace);
        await patchJob(this.env, job.job_id, {
          subtitles: reviewed.subtitles,
          provider_trace: trace,
          progress: 0.87,
          stage: "إنشاء ملفات الترجمة",
        });
        return { reviewed_count: reviewed.reviewed };
      });

      await step.do("build subtitle files", async () => {
        const row = await getJob(this.env, job.job_id);
        const segments = segmentsFrom(row.transcript);
        const subtitles = subtitlesFrom(row.subtitles);
        if (!segments.length || subtitles.length !== segments.length) {
          throw new Error("بيانات الترجمة غير مكتملة.");
        }
        const srtText = toSrt(segments, subtitles);
        const vttText = toVtt(segments, subtitles);
        const base = (this.env.PUBLIC_BASE_URL || "").replace(/\/$/, "");
        const subtitleUrl = base && row.client_id
          ? `${base}/subtitles/${encodeURIComponent(job.job_id)}.vtt?client_id=${encodeURIComponent(String(row.client_id))}`
          : null;
        await patchJob(this.env, job.job_id, {
          srt_text: srtText,
          vtt_text: vttText,
          subtitle_url: subtitleUrl,
          progress: 0.92,
          stage: "إنشاء الملخص والتحليل",
        });
        return { srt_bytes: srtText.length, vtt_bytes: vttText.length, subtitle_url: subtitleUrl };
      });

      await step.do("analyze movie", async () => {
        const row = await getJob(this.env, job.job_id);
        const segments = segmentsFrom(row.transcript);
        const subtitles = subtitlesFrom(row.subtitles);
        const trace = traceFrom(row.provider_trace);
        const summary = await analyzeMovie(this.env, segments, subtitles, trace);
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
        const trace = traceFrom(row.provider_trace);
        await recordTrace(this.env, trace, row.source_language || plan.language, plan.bucket);
        return { samples_recorded: trace.length };
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

      return {
        job_id: job.job_id,
        status: "completed",
        playback_url: retained.playback_url,
        language: asrCheckpoint.detected,
        asr_route: asrCheckpoint.asr_route,
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
    if (url.pathname === "/health") {
      return json({ ok: true, service: "h-ai-smart-media-orchestrator", version: VERSION });
    }

    if (request.method === "POST" && url.pathname === "/jobs") {
      if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);
      const body = await request.json<any>();
      if (!safeUrl(body?.source_url)) return json({ error: "invalid_source_url" }, 400);
      const id = String(body?.job_id || crypto.randomUUID()).slice(0, 100);
      const params: JobPayload = {
        job_id: id,
        source_url: String(body.source_url),
        title: cleanTitle(body?.title || titleFromUrl(String(body.source_url))),
        retention: normalizeRetention(body?.retention),
        language: normalizeLanguage(body?.language),
        duration_ms: Math.max(0, Number(body?.duration_ms || 0)),
      };
      const instance = await env.SMART_MEDIA_WORKFLOW.create({ id, params });
      return json({ status: "accepted", job_id: instance.id, version: VERSION }, 202);
    }

    if (request.method === "GET" && url.pathname.startsWith("/jobs/")) {
      if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);
      const id = decodeURIComponent(url.pathname.slice(6));
      const instance = await env.SMART_MEDIA_WORKFLOW.get(id);
      return json({ job_id: id, workflow: await instance.status(), version: VERSION });
    }

    if ((request.method === "GET" || request.method === "HEAD") && url.pathname.startsWith("/media/")) {
      return serveMovie(request, env, url.pathname.slice(7));
    }

    if (request.method === "GET" && url.pathname.startsWith("/subtitles/")) {
      return serveSubtitle(env, url);
    }

    return json({ error: "not_found" }, 404);
  },
} satisfies ExportedHandler<Env>;

async function chooseRoute(env: Env, job: JobPayload): Promise<RoutePlan> {
  const language = normalizeLanguage(job.language);
  const bucket = durationBucket(job.duration_ms || 2 * 60 * 60_000);
  const complexLanguage = ["ja", "hi", "ko", "zh"].includes(language);
  const qualityHardGate = language === "auto" || complexLanguage || bucket === "long";
  const stats = await getProviderStats(env, language, bucket).catch(() => [] as any[]);
  const turbo = stats.find((x) => x.provider === `groq:${TURBO}`);
  const v3 = stats.find((x) => x.provider === `groq:${LARGE}`);

  if (qualityHardGate) {
    if (
      language !== "auto" && !complexLanguage && turbo && Number(turbo.samples || 0) >= 8 &&
      successRate(turbo) >= 0.985 && Number(turbo.quality_score || 0) >= 0.92 &&
      (!v3 || Number(turbo.avg_latency_ms || Infinity) < Number(v3.avg_latency_ms || Infinity) * 0.82)
    ) {
      return { language, bucket, primary: TURBO, fallback: LARGE, reason: "learned-turbo-high-confidence" };
    }
    return { language, bucket, primary: LARGE, fallback: TURBO, reason: "quality-hard-gate" };
  }

  if (turbo && Number(turbo.samples || 0) >= 4 && (successRate(turbo) < 0.95 || Number(turbo.quality_score || 0) < 0.86)) {
    return { language, bucket, primary: LARGE, fallback: TURBO, reason: "learned-turbo-unreliable" };
  }
  return { language, bucket, primary: TURBO, fallback: LARGE, reason: "fast-known-short" };
}

async function serveMovie(request: Request, env: Env, encodedKey: string): Promise<Response> {
  const key = decodeURIComponent(encodedKey);
  const object = await env.MOVIES.get(key, { range: request.headers.get("range") || undefined });
  if (!object) return new Response("Not found", { status: 404, headers: cors() });

  const expires = object.customMetadata?.expires_at;
  if (expires && Date.parse(expires) <= Date.now()) {
    await env.MOVIES.delete(key).catch(() => undefined);
    return new Response("Expired", { status: 410, headers: cors() });
  }

  const headers = new Headers(cors());
  object.writeHttpMetadata(headers);
  headers.set("etag", object.httpEtag);
  headers.set("accept-ranges", "bytes");
  headers.set("cache-control", "private, max-age=300");
  if (request.method === "HEAD") {
    headers.set("content-length", String(object.size));
    return new Response(null, { headers });
  }
  if (!object.body) return new Response("Not found", { status: 404, headers });
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

async function serveSubtitle(env: Env, url: URL): Promise<Response> {
  const file = decodeURIComponent(url.pathname.slice("/subtitles/".length));
  const id = file.replace(/\.(vtt|srt)$/i, "");
  const clientId = String(url.searchParams.get("client_id") || "");
  if (!id || !clientId) return new Response("Bad request", { status: 400, headers: cors() });
  const row = await getJob(env, id).catch(() => null);
  if (!row || String(row.client_id || "") !== clientId || row.status !== "completed") {
    return new Response("Not found", { status: 404, headers: cors() });
  }
  const isSrt = /\.srt$/i.test(file);
  const text = String(isSrt ? row.srt_text || "" : row.vtt_text || "");
  if (!text) return new Response("Not found", { status: 404, headers: cors() });
  return new Response(text, {
    headers: {
      ...cors(),
      "content-type": isSrt ? "application/x-subrip; charset=utf-8" : "text/vtt; charset=utf-8",
      "cache-control": "private, max-age=300",
    },
  });
}

function successRate(row: any): number {
  const samples = Math.max(0, Number(row?.samples || 0));
  return samples ? Math.max(0, Number(row?.successes || 0)) / samples : 0;
}

function cleanTitle(value: unknown): string {
  return String(value || "Movie").replace(/\s+/g, " ").trim().slice(0, 180) || "Movie";
}

function authorized(req: Request, env: Env): boolean {
  return !!env.ORCHESTRATOR_TOKEN && req.headers.get("x-orchestrator-token") === env.ORCHESTRATOR_TOKEN;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors(), "content-type": "application/json; charset=utf-8" },
  });
}

function cors(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,HEAD,POST,OPTIONS",
    "access-control-allow-headers": "content-type,x-orchestrator-token,range",
    "access-control-expose-headers": "content-range,content-length,etag,accept-ranges",
  };
}
