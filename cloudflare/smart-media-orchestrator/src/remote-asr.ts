export type RemoteAsrTrace = {
  task: string;
  provider: string;
  latency_ms: number;
  ok: boolean;
  quality?: number;
  error?: string;
};

export type RemoteAsrEnv = {
  GROQ_API_KEY: string;
  GEMINI_API_KEY?: string;
  AUDIO_EXTRACTOR_URL?: string;
  AUDIO_EXTRACTOR_TOKEN?: string;
};

export type RemoteAsrResult = {
  language?: string;
  segments: Array<Record<string, unknown>>;
  route: string;
  source_size_bytes?: number | null;
  source_mime?: string;
};

export type RemoteProbe = {
  url: string;
  sizeBytes: number | null;
  mimeType: string;
  acceptsRanges: boolean;
};

const GROQ_TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions";
const GEMINI_UPLOAD = "https://generativelanguage.googleapis.com/upload/v1beta/files";
const GEMINI_API = "https://generativelanguage.googleapis.com/v1beta";
const GEMINI_MODEL = "gemini-3.8-flash";

// Groq documents 25 MB/file on free tier. Keep margin for provider/proxy variation.
export const GROQ_DIRECT_SAFE_BYTES = 24 * 1024 * 1024;
// Gemini File API documents a 2 GB per-file limit on free tier. Use decimal GB conservatively.
export const GEMINI_FREE_FILE_BYTES = 2_000_000_000;

export async function transcribeRemoteMedia(
  env: RemoteAsrEnv,
  input: {
    sourceUrl: string;
    title: string;
    language: string;
    primaryModel: string;
    fallbackModel: string;
  },
  trace: RemoteAsrTrace[],
): Promise<RemoteAsrResult> {
  const probe = await probeRemoteMedia(input.sourceUrl);
  const size = probe.sizeBytes;

  // Small direct files use Whisper. This remains the highest-quality fast path.
  if (size != null && size <= GROQ_DIRECT_SAFE_BYTES) {
    return await groqWithFallback(env, input, trace, probe);
  }

  // If an external extractor is configured, prefer it for large movies. It can
  // remux/extract only the audio and feed <25 MB chunks to Whisper without using
  // the phone CPU or uploading the full movie to the ASR provider.
  if (env.AUDIO_EXTRACTOR_URL) {
    try {
      return await extractorTranscribe(env, input, trace, probe);
    } catch (error) {
      trace.push({
        task: "asr",
        provider: "audio-extractor",
        latency_ms: 0,
        ok: false,
        quality: 0.5,
        error: shortError(error),
      });
    }
  }

  // Cloud-only free fallback for typical <=2 GB movie files. Gemini receives the
  // remote file by server-side streaming and returns timestamped source dialogue.
  // Whisper remains preferred whenever a dedicated extractor is available.
  if (env.GEMINI_API_KEY && size != null && size > 0 && size <= GEMINI_FREE_FILE_BYTES && isGeminiVideoMime(probe.mimeType, input.sourceUrl)) {
    return await geminiVideoTranscribe(env, input, trace, probe);
  }

  // Some servers hide Content-Length. Trying Whisper once is useful for small
  // signed URLs whose size cannot be probed. Provider 413 errors stay explicit.
  if (size == null) {
    try {
      return await groqWithFallback(env, input, trace, probe);
    } catch (error) {
      throw new Error(
        `تعذر تحديد حجم الفيلم، ولم يقبل Whisper الرابط مباشرة. ${shortError(error)}`,
      );
    }
  }

  if (size > GEMINI_FREE_FILE_BYTES) {
    throw new Error(
      "الفيلم أكبر من 2GB. للحفاظ على الجودة والمجانية يلزم تفعيل AUDIO_EXTRACTOR_URL ليُستخرج الصوت سحابيًا ثم يُقسّم إلى أجزاء Whisper صغيرة.",
    );
  }

  throw new Error(
    "الفيلم أكبر من حد Whisper المباشر. فعّل Gemini أو AUDIO_EXTRACTOR_URL لمسار الأفلام الطويلة.",
  );
}

export async function probeRemoteMedia(sourceUrl: string): Promise<RemoteProbe> {
  let sizeBytes: number | null = null;
  let mimeType = inferMime(sourceUrl, "application/octet-stream");
  let acceptsRanges = false;
  let finalUrl = sourceUrl;

  try {
    const head = await fetch(sourceUrl, { method: "HEAD", redirect: "follow" });
    finalUrl = head.url || sourceUrl;
    if (head.ok) {
      sizeBytes = parseLength(head.headers.get("content-length"));
      mimeType = inferMime(finalUrl, head.headers.get("content-type") || mimeType);
      acceptsRanges = /bytes/i.test(head.headers.get("accept-ranges") || "");
    }
    await head.body?.cancel().catch(() => undefined);
  } catch {
    // Range probe below is the fallback.
  }

  if (sizeBytes == null) {
    try {
      const ranged = await fetch(sourceUrl, {
        method: "GET",
        headers: { Range: "bytes=0-0" },
        redirect: "follow",
      });
      finalUrl = ranged.url || finalUrl;
      const contentRange = ranged.headers.get("content-range") || "";
      const totalMatch = /\/([0-9]+)$/.exec(contentRange);
      if (totalMatch) sizeBytes = parseLength(totalMatch[1]);
      if (sizeBytes == null && ranged.status === 200) {
        sizeBytes = parseLength(ranged.headers.get("content-length"));
      }
      mimeType = inferMime(finalUrl, ranged.headers.get("content-type") || mimeType);
      acceptsRanges = ranged.status === 206 || /bytes/i.test(ranged.headers.get("accept-ranges") || "");
      await ranged.body?.cancel().catch(() => undefined);
    } catch {
      // Keep unknown size; caller decides the fallback.
    }
  }

  return { url: finalUrl, sizeBytes, mimeType, acceptsRanges };
}

async function groqWithFallback(
  env: RemoteAsrEnv,
  input: { sourceUrl: string; language: string; primaryModel: string; fallbackModel: string },
  trace: RemoteAsrTrace[],
  probe: RemoteProbe,
): Promise<RemoteAsrResult> {
  let lastError: unknown = null;
  for (const model of [input.primaryModel, input.fallbackModel]) {
    try {
      const root = await groqTranscribe(env, input.sourceUrl, model, input.language, trace);
      return {
        language: String(root?.language || input.language || "auto"),
        segments: Array.isArray(root?.segments) ? root.segments : [],
        route: `groq:${model}:direct-url`,
        source_size_bytes: probe.sizeBytes,
        source_mime: probe.mimeType,
      };
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error("تعذر فهم صوت الفيلم عبر Whisper.");
}

async function groqTranscribe(
  env: RemoteAsrEnv,
  sourceUrl: string,
  model: string,
  language: string,
  trace: RemoteAsrTrace[],
): Promise<any> {
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
  trace.push({
    task: "asr",
    provider: `groq:${model}`,
    latency_ms: Date.now() - started,
    ok: response.ok,
    quality: response.ok ? (model === "whisper-large-v3" ? 0.96 : 0.91) : 0.45,
    error: response.ok ? undefined : `http_${response.status}`,
  });
  if (!response.ok) throw new Error(`Groq ${response.status}: ${text.slice(0, 260)}`);
  return JSON.parse(text);
}

async function extractorTranscribe(
  env: RemoteAsrEnv,
  input: { sourceUrl: string; title: string; language: string; primaryModel: string; fallbackModel: string },
  trace: RemoteAsrTrace[],
  probe: RemoteProbe,
): Promise<RemoteAsrResult> {
  const base = String(env.AUDIO_EXTRACTOR_URL || "").replace(/\/$/, "");
  const started = Date.now();
  const response = await fetch(`${base}/transcribe`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(env.AUDIO_EXTRACTOR_TOKEN ? { Authorization: `Bearer ${env.AUDIO_EXTRACTOR_TOKEN}` } : {}),
    },
    body: JSON.stringify({
      source_url: input.sourceUrl,
      title: input.title,
      language: input.language,
      primary_model: input.primaryModel,
      fallback_model: input.fallbackModel,
      max_chunk_bytes: GROQ_DIRECT_SAFE_BYTES,
    }),
  });
  const text = await response.text();
  trace.push({
    task: "asr",
    provider: "audio-extractor+groq",
    latency_ms: Date.now() - started,
    ok: response.ok,
    quality: response.ok ? 0.96 : 0.45,
    error: response.ok ? undefined : `http_${response.status}`,
  });
  if (!response.ok) throw new Error(`Extractor ${response.status}: ${text.slice(0, 260)}`);
  const root = JSON.parse(text);
  const segments = Array.isArray(root?.segments) ? root.segments : [];
  if (!segments.length) throw new Error("لم يُرجع مستخرج الصوت أي حوار.");
  return {
    language: String(root?.language || input.language || "auto"),
    segments,
    route: "audio-extractor+groq-chunks",
    source_size_bytes: probe.sizeBytes,
    source_mime: probe.mimeType,
  };
}

async function geminiVideoTranscribe(
  env: RemoteAsrEnv,
  input: { sourceUrl: string; title: string; language: string },
  trace: RemoteAsrTrace[],
  probe: RemoteProbe,
): Promise<RemoteAsrResult> {
  const key = String(env.GEMINI_API_KEY || "");
  if (!key || probe.sizeBytes == null) throw new Error("Gemini large-file route is unavailable.");

  const started = Date.now();
  try {
    const uploaded = await uploadRemoteToGemini(key, input.sourceUrl, input.title, probe);
    const active = await waitForGeminiFile(key, uploaded);
    const root = await generateGeminiTranscript(key, active.uri, active.mimeType || probe.mimeType, input.language);
    const parsed = parseGeminiJson(root);
    const segments = Array.isArray(parsed?.segments) ? parsed.segments : [];
    if (!segments.length) throw new Error("Gemini لم يُرجع حوارًا زمنيًا صالحًا.");
    trace.push({
      task: "asr",
      provider: `gemini:${GEMINI_MODEL}:video-file`,
      latency_ms: Date.now() - started,
      ok: true,
      quality: 0.88,
    });
    return {
      language: String(parsed?.language || input.language || "auto"),
      segments,
      route: `gemini:${GEMINI_MODEL}:file-api`,
      source_size_bytes: probe.sizeBytes,
      source_mime: probe.mimeType,
    };
  } catch (error) {
    trace.push({
      task: "asr",
      provider: `gemini:${GEMINI_MODEL}:video-file`,
      latency_ms: Date.now() - started,
      ok: false,
      quality: 0.45,
      error: shortError(error),
    });
    throw error;
  }
}

async function uploadRemoteToGemini(
  apiKey: string,
  sourceUrl: string,
  title: string,
  probe: RemoteProbe,
): Promise<{ name: string; uri: string; mimeType: string; state?: string }> {
  const mimeType = inferMime(sourceUrl, probe.mimeType);
  const start = await fetch(GEMINI_UPLOAD, {
    method: "POST",
    headers: {
      "x-goog-api-key": apiKey,
      "X-Goog-Upload-Protocol": "resumable",
      "X-Goog-Upload-Command": "start",
      "X-Goog-Upload-Header-Content-Length": String(probe.sizeBytes),
      "X-Goog-Upload-Header-Content-Type": mimeType,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ file: { display_name: title.slice(0, 120) || "H AI movie" } }),
  });
  const uploadUrl = start.headers.get("x-goog-upload-url") || "";
  if (!start.ok || !uploadUrl) throw new Error(`Gemini upload init ${start.status}`);

  const source = await fetch(sourceUrl, { redirect: "follow" });
  if (!source.ok || !source.body) throw new Error(`Source stream ${source.status}`);

  const uploaded = await fetch(uploadUrl, {
    method: "POST",
    headers: {
      "Content-Length": String(probe.sizeBytes),
      "X-Goog-Upload-Offset": "0",
      "X-Goog-Upload-Command": "upload, finalize",
    },
    body: source.body,
  });
  const text = await uploaded.text();
  if (!uploaded.ok) throw new Error(`Gemini upload ${uploaded.status}: ${text.slice(0, 220)}`);
  const root = JSON.parse(text);
  const file = root?.file || root;
  const name = String(file?.name || "");
  const uri = String(file?.uri || "");
  if (!name || !uri) throw new Error("Gemini file metadata missing.");
  return {
    name,
    uri,
    mimeType: String(file?.mimeType || file?.mime_type || mimeType),
    state: normalizeGeminiState(file?.state),
  };
}

async function waitForGeminiFile(
  apiKey: string,
  file: { name: string; uri: string; mimeType: string; state?: string },
): Promise<{ name: string; uri: string; mimeType: string }> {
  let current = file;
  for (let attempt = 0; attempt < 100; attempt++) {
    const state = normalizeGeminiState(current.state);
    if (state === "ACTIVE" || !state) return current;
    if (state === "FAILED") throw new Error("Gemini failed to process the uploaded movie.");
    await sleep(Math.min(5000, 1500 + attempt * 100));
    const response = await fetch(`${GEMINI_API}/${current.name}`, {
      headers: { "x-goog-api-key": apiKey },
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`Gemini file status ${response.status}: ${text.slice(0, 180)}`);
    const root = JSON.parse(text);
    current = {
      name: String(root?.name || current.name),
      uri: String(root?.uri || current.uri),
      mimeType: String(root?.mimeType || root?.mime_type || current.mimeType),
      state: normalizeGeminiState(root?.state),
    };
  }
  throw new Error("Gemini file processing timed out.");
}

async function generateGeminiTranscript(apiKey: string, fileUri: string, mimeType: string, language: string): Promise<any> {
  const prompt = [
    "Transcribe every intelligible spoken line in this movie in the ORIGINAL spoken language.",
    "Do not translate, summarize, merge, or omit ordinary dialogue.",
    "Return strict JSON only with this shape:",
    '{"language":"en","segments":[{"start_ms":0,"end_ms":1200,"text":"..."}]}',
    "Use millisecond timestamps, preserve chronological order, and keep one natural subtitle-sized utterance per segment.",
    language !== "auto" ? `Expected language hint: ${language}.` : "Detect the spoken language automatically.",
  ].join("\n");

  const response = await fetch(`${GEMINI_API}/models/${GEMINI_MODEL}:generateContent`, {
    method: "POST",
    headers: { "x-goog-api-key": apiKey, "content-type": "application/json" },
    body: JSON.stringify({
      contents: [{
        role: "user",
        parts: [
          {
            fileData: { mimeType, fileUri },
            mediaResolution: { level: "MEDIA_RESOLUTION_LOW" },
            mediaProcessing: "STATIC",
          },
          { text: prompt },
        ],
      }],
      generationConfig: {
        temperature: 0,
        maxOutputTokens: 65536,
        responseMimeType: "application/json",
      },
    }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Gemini transcript ${response.status}: ${text.slice(0, 260)}`);
  return JSON.parse(text);
}

function parseGeminiJson(root: any): any {
  const text = String(root?.candidates?.[0]?.content?.parts?.map((p: any) => p?.text || "").join("") || "{}").trim();
  const cleaned = text.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  return JSON.parse(cleaned || "{}");
}

function parseLength(value: string | null): number | null {
  if (!value) return null;
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function inferMime(url: string, headerMime: string): string {
  const raw = String(headerMime || "").split(";")[0].trim().toLowerCase();
  if (raw.startsWith("video/") || raw.startsWith("audio/")) return raw;
  const path = (() => { try { return new URL(url).pathname.toLowerCase(); } catch { return url.toLowerCase(); } })();
  if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "video/mp4";
  if (path.endsWith(".webm")) return "video/webm";
  if (path.endsWith(".mov")) return "video/quicktime";
  if (path.endsWith(".mpeg") || path.endsWith(".mpg")) return "video/mpeg";
  if (path.endsWith(".mp3")) return "audio/mpeg";
  if (path.endsWith(".m4a")) return "audio/mp4";
  if (path.endsWith(".ogg") || path.endsWith(".opus")) return "audio/ogg";
  if (path.endsWith(".wav")) return "audio/wav";
  if (path.endsWith(".flac")) return "audio/flac";
  return raw || "application/octet-stream";
}

function isGeminiVideoMime(mime: string, url: string): boolean {
  return inferMime(url, mime).startsWith("video/");
}

function normalizeGeminiState(value: unknown): string {
  if (typeof value === "string") return value.toUpperCase();
  if (value && typeof value === "object" && "name" in (value as Record<string, unknown>)) {
    return String((value as Record<string, unknown>).name || "").toUpperCase();
  }
  return "";
}

function shortError(error: unknown): string {
  return (error instanceof Error ? error.message : String(error || "provider_error")).slice(0, 180);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
