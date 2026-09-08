import { WorkflowEntrypoint } from "cloudflare:workers";

const MAX_AUDIO_BYTES = 24 * 1024 * 1024;
const STALE_AUDIO_MS = 24 * 60 * 60 * 1000;

export class MovieAudioWorkflow extends WorkflowEntrypoint {
  async run(event, step) {
    const params = event.payload;

    const result = await step.do(
      "normalize-and-transcribe",
      {
        retries: { limit: 4, delay: "5 seconds", backoff: "exponential" },
        timeout: "5 minutes",
      },
      async () => {
        const stored = await this.env.MOVIE_AUDIO.get(params.objectKey);
        if (!stored) throw new Error("r2_audio_missing");

        const source = await stored.blob();
        const normalizeForm = new FormData();
        normalizeForm.set(
          "audio",
          new File([source], params.filename || "audio.bin", {
            type: params.contentType || "application/octet-stream",
          }),
        );

        const normalized = await fetch(`${params.renderNormalizerUrl}/normalize`, {
          method: "POST",
          headers: { "x-audio-extractor-token": this.env.AUDIO_EXTRACTOR_TOKEN },
          body: normalizeForm,
        });
        if (!normalized.ok) {
          const detail = (await normalized.text()).slice(0, 300);
          throw new Error(`normalizer_${normalized.status}:${detail}`);
        }

        const normalizedAudio = await normalized.blob();
        if (!normalizedAudio.size || normalizedAudio.size > MAX_AUDIO_BYTES) {
          throw new Error("normalized_audio_size_invalid");
        }

        const asrForm = new FormData();
        asrForm.set("mode", "asr");
        asrForm.set("provider", params.provider || "groq");
        asrForm.set("offset_ms", String(params.offsetMs || 0));
        asrForm.set("duration_ms", String(params.durationMs || 0));
        if (params.deviceHash) asrForm.set("device_hash", params.deviceHash);
        asrForm.set("audio", new File([normalizedAudio], "normalized.ogg", { type: "audio/ogg" }));

        const transcribed = await fetch(`${params.renderAsrUrl}/asr`, {
          method: "POST",
          headers: { "x-audio-extractor-token": this.env.AUDIO_EXTRACTOR_TOKEN },
          body: asrForm,
        });
        const text = await transcribed.text();
        if (!transcribed.ok) throw new Error(`asr_${transcribed.status}:${text.slice(0, 300)}`);

        let root;
        try {
          root = JSON.parse(text || "{}");
        } catch {
          throw new Error("asr_invalid_json");
        }
        if (root.status && root.status !== "completed") {
          throw new Error(`asr_unexpected_status:${root.status}`);
        }
        if (!Array.isArray(root.segments) || root.segments.length === 0) {
          throw new Error("asr_empty_segments");
        }
        return root;
      },
    );

    await step.do("delete-temporary-audio", async () => {
      await this.env.MOVIE_AUDIO.delete(params.objectKey);
      return { deleted: true };
    });

    return result;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return Response.json({ ok: true, service: "manzl-movie-workflow" });
    }

    if (!authorized(request, env)) {
      return Response.json({ error: "invalid_token" }, { status: 401 });
    }

    if (request.method === "POST" && url.pathname === "/asr") {
      return startAsr(request, env);
    }

    if (request.method === "GET" && url.pathname.startsWith("/jobs/")) {
      return getJob(url.pathname.slice("/jobs/".length), env);
    }

    return Response.json({ error: "not_found" }, { status: 404 });
  },

  async scheduled(_controller, env, ctx) {
    ctx.waitUntil(cleanupStaleAudio(env));
  },
};

async function startAsr(request, env) {
  const form = await request.formData();
  const audio = form.get("audio");
  if (!(audio instanceof File) || audio.size <= 0) {
    return Response.json({ error: "audio_required" }, { status: 400 });
  }
  if (audio.size > MAX_AUDIO_BYTES) {
    return Response.json({ error: "audio_too_large" }, { status: 413 });
  }
  if ((audio.type || "").toLowerCase().startsWith("video/")) {
    return Response.json({ error: "video_upload_forbidden" }, { status: 415 });
  }

  const renderAsrUrl = cleanHttps(form.get("render_asr_url"));
  const renderNormalizerUrl = cleanHttps(form.get("render_normalizer_url"));
  if (!renderAsrUrl || !renderNormalizerUrl) {
    return Response.json({ error: "render_routes_required" }, { status: 400 });
  }

  const jobId = crypto.randomUUID();
  const objectKey = `audio/${jobId}`;
  await env.MOVIE_AUDIO.put(objectKey, audio.stream(), {
    httpMetadata: { contentType: audio.type || "application/octet-stream" },
    customMetadata: { purpose: "movie-translator-asr", temporary: "true" },
  });

  try {
    await env.MOVIE_WORKFLOW.create({
      id: jobId,
      params: {
        objectKey,
        filename: audio.name || "audio.bin",
        contentType: audio.type || "application/octet-stream",
        offsetMs: safeInt(form.get("offset_ms")),
        durationMs: safeInt(form.get("duration_ms")),
        provider: String(form.get("provider") || "groq"),
        deviceHash: String(form.get("device_hash") || "").slice(0, 128),
        renderAsrUrl,
        renderNormalizerUrl,
      },
    });
  } catch (error) {
    await env.MOVIE_AUDIO.delete(objectKey);
    throw error;
  }

  return Response.json(
    { status: "in_progress", job_id: jobId, provider: "cloudflare-workflow+r2", retry_after_ms: 5000 },
    { status: 202 },
  );
}

async function getJob(id, env) {
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(id)) {
    return Response.json({ error: "invalid_job_id" }, { status: 400 });
  }

  try {
    const instance = await env.MOVIE_WORKFLOW.get(id);
    const state = await instance.status();
    if (state.status === "complete") {
      return Response.json({ status: "completed", ...(state.output || {}) });
    }
    if (state.status === "errored" || state.status === "terminated") {
      await env.MOVIE_AUDIO.delete(`audio/${id}`);
      return Response.json({
        status: "failed",
        message: state.error?.message || "workflow_failed",
        provider: "cloudflare-workflow+r2",
      });
    }
    return Response.json({
      status: "in_progress",
      workflow_status: state.status,
      provider: "cloudflare-workflow+r2",
      retry_after_ms: 5000,
    });
  } catch {
    return Response.json({ error: "job_not_found" }, { status: 404 });
  }
}

async function cleanupStaleAudio(env) {
  let cursor;
  const cutoff = Date.now() - STALE_AUDIO_MS;
  do {
    const listed = await env.MOVIE_AUDIO.list({ prefix: "audio/", cursor, limit: 500 });
    const stale = listed.objects.filter((item) => item.uploaded.getTime() < cutoff).map((item) => item.key);
    if (stale.length) await env.MOVIE_AUDIO.delete(stale);
    cursor = listed.truncated ? listed.cursor : undefined;
  } while (cursor);
}

function authorized(request, env) {
  const expected = String(env.AUDIO_EXTRACTOR_TOKEN || "");
  const supplied = String(request.headers.get("x-audio-extractor-token") || "");
  return expected.length >= 32 && supplied === expected;
}

function cleanHttps(value) {
  const raw = String(value || "").trim().replace(/\/$/, "");
  try {
    const url = new URL(raw);
    return url.protocol === "https:" ? url.origin : "";
  } catch {
    return "";
  }
}

function safeInt(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? Math.max(0, Math.trunc(number)) : 0;
}
