import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
const GEMINI_INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
const GEMINI_UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files";
const GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta";
const GEMINI_MODEL = "gemini-3.8-flash";
const GROQ_MODEL = "whisper-large-v3";
const MAX_PART_BYTES = 24 * 1024 * 1024;
const API_REVISION = "2026-05-20";

interface Segment {
  id: number;
  start_ms: number;
  end_ms: number;
  tr: string;
}

interface UploadedGeminiFile {
  name: string;
  uri: string;
  mimeType: string;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const groqKey = Deno.env.get("GROQ_API_KEY");
  const geminiKey = Deno.env.get("GEMINI_API_KEY");
  if (!groqKey || !geminiKey) {
    return json({
      error: "cloud_keys_missing",
      message: "GROQ_API_KEY/GEMINI_API_KEY are not configured.",
    }, 503);
  }

  try {
    const contentType = req.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
      const body = await req.json();
      const mode = String(body?.mode || "");
      if (mode === "translate_start") return await handleTranslateStart(body, geminiKey);
      if (mode === "poll") return await handlePoll(body, geminiKey);
      return json({ error: "invalid_json_mode" }, 400);
    }

    const form = await req.formData();
    const mode = String(form.get("mode") || "");
    if (mode === "asr") return await handleAsr(form, groqKey, geminiKey);

    // Older APKs used one long ASR+translation invocation. That shape is intentionally rejected:
    // Supabase Free Edge Functions have a 150-second wall-clock/idle ceiling and can time out.
    if (form.has("part_count")) {
      return json({
        error: "client_update_required",
        message: "حدّث التطبيق إلى النسخة التي تفصل الاستماع والترجمة السحابية لتجنب timeout.",
      }, 409);
    }
    return json({ error: "invalid_multipart_mode" }, 400);
  } catch (error) {
    console.error(error);
    return json({
      error: "internal_error",
      message: error instanceof Error ? error.message : String(error),
    }, 500);
  }
});

async function handleAsr(form: FormData, groqKey: string, geminiKey: string): Promise<Response> {
  const value = form.get("audio");
  if (!(value instanceof File)) return json({ error: "audio_required" }, 400);
  if (value.size <= 0 || value.size > MAX_PART_BYTES) {
    return json({ error: "audio_size_invalid", max_bytes: MAX_PART_BYTES }, 413);
  }
  const offsetMs = safeMs(form.get("offset_ms"));
  const provider = String(form.get("provider") || "groq").toLowerCase();
  const started = Date.now();

  if (provider === "groq") {
    try {
      const segments = normalizePart(await transcribeWithGroq(value, offsetMs, groqKey));
      if (!segments.length) return json({ error: "no_turkish_dialogue" }, 422);
      return json({
        status: "completed",
        segments,
        metrics: { asr_ms: Date.now() - started, provider: GROQ_MODEL },
      });
    } catch (error) {
      // Quota/transient Groq failures switch to a Gemini background job instead of holding this
      // Edge Function open until the 150-second platform limit is hit.
      console.warn("Groq ASR fallback to Gemini background", error instanceof Error ? error.message : String(error));
    }
  }

  const uploaded = await uploadGeminiFile(value, geminiKey, `movie-asr-${Date.now()}`);
  try {
    await waitForGeminiFile(uploaded.name, geminiKey);
    const interaction = await createGeminiAsrInteraction(uploaded, geminiKey);
    if (!interaction?.id) throw new Error("gemini_background_id_missing");
    if (String(interaction.status || "") === "completed") {
      const segments = parseGeminiAsr(interaction, offsetMs);
      await deleteGeminiFile(uploaded.name, geminiKey);
      return json({
        status: "completed",
        segments,
        metrics: { asr_ms: Date.now() - started, provider: `${GEMINI_MODEL}-audio` },
      });
    }
    return json({
      status: "in_progress",
      job_id: packAsrJob(String(interaction.id), offsetMs, uploaded.name),
      provider: `${GEMINI_MODEL}-audio`,
    });
  } catch (error) {
    await deleteGeminiFile(uploaded.name, geminiKey);
    throw error;
  }
}

async function handleTranslateStart(body: any, geminiKey: string): Promise<Response> {
  const source = Array.isArray(body?.segments) ? body.segments : [];
  if (!source.length || source.length > 20000) return json({ error: "segments_invalid" }, 400);
  const segments: Segment[] = source.map((item: any, index: number) => ({
    id: Number.isInteger(Number(item?.id)) ? Number(item.id) : index,
    start_ms: Math.max(0, Math.round(Number(item?.start_ms || 0))),
    end_ms: Math.max(1, Math.round(Number(item?.end_ms || 1))),
    tr: cleanTurkish(String(item?.tr || "")),
  })).filter((item: Segment) => item.tr.length > 0 && item.end_ms > item.start_ms);
  if (!segments.length) return json({ error: "segments_empty" }, 400);

  const started = Date.now();
  const interaction = await createTranslationInteraction(segments, geminiKey);
  if (!interaction?.id) throw new Error("gemini_background_id_missing");
  if (String(interaction.status || "") === "completed") {
    const subtitles = parseTranslation(interaction);
    return json({
      status: "completed",
      subtitles,
      metrics: { translation_ms: Date.now() - started, provider: GEMINI_MODEL },
    });
  }
  return json({ status: "in_progress", job_id: String(interaction.id), provider: GEMINI_MODEL });
}

async function handlePoll(body: any, geminiKey: string): Promise<Response> {
  const kind = String(body?.kind || "");
  const packedJobId = String(body?.job_id || "");
  if (!packedJobId || (kind !== "asr" && kind !== "translate")) {
    return json({ error: "poll_request_invalid" }, 400);
  }

  let interactionId = packedJobId;
  let offsetMs = 0;
  let fileName = "";
  if (kind === "asr") {
    const unpacked = unpackAsrJob(packedJobId);
    interactionId = unpacked.interactionId;
    offsetMs = unpacked.offsetMs;
    fileName = unpacked.fileName;
  }

  const interaction = await getGeminiInteraction(interactionId, geminiKey);
  const status = String(interaction?.status || "completed");
  if (status === "in_progress" || status === "queued") {
    return json({ status: "in_progress" });
  }
  if (status !== "completed") {
    if (kind === "asr" && fileName) await deleteGeminiFile(fileName, geminiKey);
    return json({
      status: status || "failed",
      message: `Gemini interaction ended with status: ${status}`,
    }, 502);
  }

  if (kind === "asr") {
    try {
      const segments = parseGeminiAsr(interaction, offsetMs);
      return json({
        status: "completed",
        segments,
        metrics: { provider: `${GEMINI_MODEL}-audio` },
      });
    } finally {
      if (fileName) await deleteGeminiFile(fileName, geminiKey);
    }
  }

  return json({
    status: "completed",
    subtitles: parseTranslation(interaction),
    metrics: { provider: GEMINI_MODEL },
  });
}

async function transcribeWithGroq(file: File, offsetMs: number, apiKey: string): Promise<Segment[]> {
  const form = new FormData();
  form.append("file", file, file.name || "audio.ogg");
  form.append("model", GROQ_MODEL);
  form.append("language", "tr");
  form.append("response_format", "verbose_json");
  form.append("temperature", "0");
  form.append("timestamp_granularities[]", "segment");

  const response = await timedFetch(GROQ_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}` },
    body: form,
  }, 85_000, "groq_timeout");
  const text = await response.text();
  if (!response.ok) throw new Error(`groq_${response.status}:${text.slice(0, 600)}`);
  const body = JSON.parse(text);
  const source = Array.isArray(body.segments) ? body.segments : [];
  return source.map((s: any) => ({
    id: 0,
    start_ms: offsetMs + Math.max(0, Math.round(Number(s.start || 0) * 1000)),
    end_ms: offsetMs + Math.max(1, Math.round(Number(s.end || 0) * 1000)),
    tr: cleanTurkish(String(s.text || "")),
  })).filter((s: Segment) => s.tr.length > 0 && s.end_ms > s.start_ms);
}

async function createGeminiAsrInteraction(uploaded: UploadedGeminiFile, apiKey: string): Promise<any> {
  const schema = {
    type: "object",
    properties: {
      segments: {
        type: "array",
        items: {
          type: "object",
          properties: {
            start_ms: { type: "integer" },
            end_ms: { type: "integer" },
            tr: { type: "string" },
          },
          required: ["start_ms", "end_ms", "tr"],
        },
      },
    },
    required: ["segments"],
  };
  const prompt = `Transcribe this Turkish movie audio accurately. Return subtitle-ready Turkish dialogue segments with start_ms and end_ms relative to THIS audio file only.\nRules:\n- Turkish transcription only; do not translate.\n- Preserve every intelligible spoken line, names, slang, particles and short replies.\n- Do not invent dialogue from music or sound effects.\n- Keep segments naturally sized for subtitles, normally 1-8 seconds.\n- start_ms/end_ms must follow the actual speech timing and be monotonic.\n- Return only the requested structured JSON.`;
  return await createBackgroundInteraction(apiKey, {
    model: GEMINI_MODEL,
    input: [
      { type: "text", text: prompt },
      { type: "audio", uri: uploaded.uri, mime_type: uploaded.mimeType },
    ],
    response_format: { type: "text", mime_type: "application/json", schema },
    generation_config: { thinking_level: "low", max_output_tokens: 65536 },
    background: true,
    store: true,
  });
}

async function createTranslationInteraction(segments: Segment[], apiKey: string): Promise<any> {
  const schema = {
    type: "object",
    properties: {
      subtitles: {
        type: "array",
        items: {
          type: "object",
          properties: { id: { type: "integer" }, ar: { type: "string" } },
          required: ["id", "ar"],
        },
      },
    },
    required: ["subtitles"],
  };
  const prompt = `You are the senior Arabic subtitle translator for a Turkish feature film. Read the ENTIRE ordered Turkish dialogue first so character relationships, names, pronouns, jokes, threats, idioms, callbacks and tone stay consistent across the whole movie.\n\nTranslate every segment into polished, natural Modern Standard Arabic that feels professionally subtitled, not machine-translated.\nRules:\n- Preserve the exact intended meaning and emotional tone.\n- Prefer natural cinematic Arabic over literal Turkish word order.\n- Preserve names and recurring terminology consistently.\n- Translate Turkish idioms by meaning, not word-for-word.\n- Keep concise subtitle phrasing without deleting information.\n- Never omit, merge, reorder, summarize, censor, explain or add dialogue.\n- Return every id exactly once and only the Arabic for that id.\n- Do not output timestamps; the app preserves the verified ASR timeline.\n\nFull movie dialogue:\n${JSON.stringify(segments.map(({ id, tr }) => ({ id, tr })))}`;
  return await createBackgroundInteraction(apiKey, {
    model: GEMINI_MODEL,
    input: prompt,
    response_format: { type: "text", mime_type: "application/json", schema },
    generation_config: { thinking_level: "low", max_output_tokens: 65536 },
    background: true,
    store: true,
  });
}

async function createBackgroundInteraction(apiKey: string, body: unknown): Promise<any> {
  const response = await timedFetch(GEMINI_INTERACTIONS_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-goog-api-key": apiKey,
      "Api-Revision": API_REVISION,
    },
    body: JSON.stringify(body),
  }, 35_000, "gemini_start_timeout");
  const text = await response.text();
  if (!response.ok) throw new Error(`gemini_${response.status}:${text.slice(0, 900)}`);
  return JSON.parse(text);
}

async function getGeminiInteraction(id: string, apiKey: string): Promise<any> {
  const response = await timedFetch(`${GEMINI_INTERACTIONS_URL}/${encodeURIComponent(id)}`, {
    headers: { "x-goog-api-key": apiKey, "Api-Revision": API_REVISION },
  }, 20_000, "gemini_poll_timeout");
  const text = await response.text();
  if (!response.ok) throw new Error(`gemini_poll_${response.status}:${text.slice(0, 600)}`);
  return JSON.parse(text);
}

function parseGeminiAsr(interaction: any, offsetMs: number): Segment[] {
  const parsed = JSON.parse(extractInteractionText(interaction));
  const source = Array.isArray(parsed?.segments) ? parsed.segments : [];
  return normalizePart(source.map((s: any) => ({
    id: 0,
    start_ms: offsetMs + Math.max(0, Math.round(Number(s.start_ms || 0))),
    end_ms: offsetMs + Math.max(1, Math.round(Number(s.end_ms || 0))),
    tr: cleanTurkish(String(s.tr || "")),
  })).filter((s: Segment) => s.tr.length > 0 && s.end_ms > s.start_ms));
}

function parseTranslation(interaction: any): Array<{ id: number; ar: string }> {
  const parsed = JSON.parse(extractInteractionText(interaction));
  const source = Array.isArray(parsed?.subtitles) ? parsed.subtitles : [];
  return source.map((item: any) => ({
    id: Number(item?.id),
    ar: cleanArabic(String(item?.ar || "")),
  })).filter((item: any) => Number.isInteger(item.id) && item.ar.length > 0);
}

function extractInteractionText(interaction: any): string {
  if (typeof interaction?.output_text === "string" && interaction.output_text.trim()) {
    return interaction.output_text.trim();
  }
  const steps = Array.isArray(interaction?.steps) ? interaction.steps : [];
  for (let i = steps.length - 1; i >= 0; i--) {
    const step = steps[i];
    if (step?.type !== "model_output" || !Array.isArray(step.content)) continue;
    const texts = step.content
      .filter((part: any) => part?.type === "text" && typeof part.text === "string")
      .map((part: any) => part.text);
    if (texts.length) return texts.join("").trim();
  }
  throw new Error("gemini_empty_output");
}

async function uploadGeminiFile(file: File, apiKey: string, displayName: string): Promise<UploadedGeminiFile> {
  const start = await timedFetch(GEMINI_UPLOAD_URL, {
    method: "POST",
    headers: {
      "x-goog-api-key": apiKey,
      "X-Goog-Upload-Protocol": "resumable",
      "X-Goog-Upload-Command": "start",
      "X-Goog-Upload-Header-Content-Length": String(file.size),
      "X-Goog-Upload-Header-Content-Type": file.type || "audio/ogg",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ file: { display_name: displayName } }),
  }, 25_000, "gemini_upload_start_timeout");
  if (!start.ok) throw new Error(`gemini_upload_start_${start.status}:${(await start.text()).slice(0, 500)}`);
  const uploadUrl = start.headers.get("x-goog-upload-url");
  if (!uploadUrl) throw new Error("gemini_upload_url_missing");

  const upload = await timedFetch(uploadUrl, {
    method: "POST",
    headers: {
      "Content-Length": String(file.size),
      "X-Goog-Upload-Offset": "0",
      "X-Goog-Upload-Command": "upload, finalize",
      "Content-Type": file.type || "audio/ogg",
    },
    body: file,
  }, 80_000, "gemini_upload_timeout");
  const text = await upload.text();
  if (!upload.ok) throw new Error(`gemini_upload_${upload.status}:${text.slice(0, 500)}`);
  const info = JSON.parse(text)?.file;
  if (!info?.name || !info?.uri) throw new Error("gemini_file_invalid");
  return {
    name: String(info.name),
    uri: String(info.uri),
    mimeType: String(info.mimeType || info.mime_type || file.type || "audio/ogg"),
  };
}

async function waitForGeminiFile(name: string, apiKey: string): Promise<void> {
  for (let attempt = 0; attempt < 30; attempt++) {
    const response = await timedFetch(`${GEMINI_API_BASE}/${name}`, {
      headers: { "x-goog-api-key": apiKey },
    }, 10_000, "gemini_file_status_timeout");
    const text = await response.text();
    if (!response.ok) throw new Error(`gemini_file_get_${response.status}:${text.slice(0, 300)}`);
    const state = String(JSON.parse(text)?.state || "ACTIVE");
    if (state === "ACTIVE" || state === "STATE_UNSPECIFIED") return;
    if (state === "FAILED") throw new Error("gemini_file_processing_failed");
    await sleep(500);
  }
  throw new Error("gemini_file_processing_timeout");
}

async function deleteGeminiFile(name: string, apiKey: string): Promise<void> {
  if (!name.startsWith("files/")) return;
  try {
    await timedFetch(`${GEMINI_API_BASE}/${name}`, {
      method: "DELETE",
      headers: { "x-goog-api-key": apiKey },
    }, 10_000, "gemini_delete_timeout");
  } catch {
    // Best effort. Gemini file retention will clean up abandoned uploads automatically.
  }
}

async function timedFetch(url: string, init: RequestInit, timeoutMs: number, label: string): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (error) {
    if (controller.signal.aborted) throw new Error(label);
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function normalizePart(input: Segment[]): Segment[] {
  const output: Segment[] = [];
  for (const raw of input.sort((a, b) => a.start_ms - b.start_ms)) {
    const tr = cleanTurkish(raw.tr);
    if (!tr) continue;
    const start = Math.max(0, Math.round(raw.start_ms));
    const end = Math.max(start + 120, Math.round(raw.end_ms));
    output.push({ id: output.length, start_ms: start, end_ms: end, tr });
  }
  return output;
}

function packAsrJob(interactionId: string, offsetMs: number, fileName: string): string {
  return `${interactionId}~${Math.max(0, Math.round(offsetMs))}~${encodeURIComponent(fileName)}`;
}

function unpackAsrJob(value: string): { interactionId: string; offsetMs: number; fileName: string } {
  const first = value.indexOf("~");
  const second = value.indexOf("~", first + 1);
  if (first <= 0 || second <= first) throw new Error("asr_job_id_invalid");
  return {
    interactionId: value.slice(0, first),
    offsetMs: safeMs(value.slice(first + 1, second)),
    fileName: decodeURIComponent(value.slice(second + 1)),
  };
}

function cleanTurkish(value: string): string {
  return value.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function cleanArabic(value: string): string {
  return value
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .replace(/,/g, "،")
    .replace(/;/g, "؛")
    .replace(/\?/g, "؟")
    .trim();
}

function safeMs(value: FormDataEntryValue | string | null): number {
  const parsed = Number(value || 0);
  return Number.isFinite(parsed) && parsed >= 0 ? Math.round(parsed) : 0;
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders(), "Content-Type": "application/json; charset=utf-8" },
  });
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
  };
}
