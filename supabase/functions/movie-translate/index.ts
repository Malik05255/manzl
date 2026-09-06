import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
const GEMINI_INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
const GEMINI_UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files";
const GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta";
const GEMINI_MODEL = "gemini-3.8-flash";
const GROQ_MODEL = "whisper-large-v3";
const MAX_PART_BYTES = 24 * 1024 * 1024;
const MAX_PARTS = 2;

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
    return json({ error: "cloud_keys_missing", message: "GROQ_API_KEY/GEMINI_API_KEY are not configured." }, 503);
  }

  const started = Date.now();
  try {
    const form = await req.formData();
    const requestedCount = Number(form.get("part_count") || 1);
    if (!Number.isInteger(requestedCount) || requestedCount < 1 || requestedCount > MAX_PARTS) {
      return json({ error: "invalid_part_count" }, 400);
    }

    const parts = [] as Array<{ file: File; offsetMs: number; durationMs: number }>;
    for (let i = 0; i < requestedCount; i++) {
      const value = form.get(`audio${i}`);
      if (!(value instanceof File)) return json({ error: `audio${i}_required` }, 400);
      if (value.size <= 0 || value.size > MAX_PART_BYTES) {
        return json({ error: `audio${i}_size_invalid`, max_bytes: MAX_PART_BYTES }, 413);
      }
      parts.push({
        file: value,
        offsetMs: safeMs(form.get(`offset${i}_ms`)),
        durationMs: safeMs(form.get(`duration${i}_ms`)),
      });
    }

    const asrStarted = Date.now();
    const providers: string[] = [];
    const tasks = parts.map(async (part, index) => {
      if (index === 0) {
        try {
          const result = await transcribeWithGroq(part.file, part.offsetMs, groqKey);
          providers[index] = GROQ_MODEL;
          return result;
        } catch (error) {
          console.warn("Groq ASR fallback to Gemini", error instanceof Error ? error.message : String(error));
          const result = await transcribeWithGemini(part.file, part.offsetMs, geminiKey, `part-${index + 1}`);
          providers[index] = `${GEMINI_MODEL}-audio-fallback`;
          return result;
        }
      }
      const result = await transcribeWithGemini(part.file, part.offsetMs, geminiKey, `part-${index + 1}`);
      providers[index] = `${GEMINI_MODEL}-audio`;
      return result;
    });

    const segments = normalizeTimeline((await Promise.all(tasks)).flat().sort((a, b) => a.start_ms - b.start_ms));
    if (!segments.length) return json({ error: "no_turkish_dialogue" }, 422);

    const asrMs = Date.now() - asrStarted;
    const translationStarted = Date.now();
    const translated = await translateMovieAsOneContext(segments, geminiKey);
    const translationMs = Date.now() - translationStarted;

    const byId = new Map<number, string>();
    for (const item of translated) {
      if (Number.isInteger(item.id) && item.ar) byId.set(item.id, item.ar.trim());
    }
    const subtitles = segments.map((s) => ({ ...s, ar: byId.get(s.id) || "" }));
    const missing = subtitles.filter((s) => !s.ar).map((s) => s.id);
    if (missing.length) return json({ error: "translation_incomplete", missing_ids: missing.slice(0, 100) }, 502);

    return json({
      subtitles,
      metrics: {
        asr_ms: asrMs,
        translation_ms: translationMs,
        total_ms: Date.now() - started,
        providers: `${providers.filter(Boolean).join(" + ")} → ${GEMINI_MODEL}`,
        groq_model: GROQ_MODEL,
        gemini_model: GEMINI_MODEL,
        parts: requestedCount,
      },
    });
  } catch (error) {
    console.error(error);
    return json({ error: "internal_error", message: error instanceof Error ? error.message : String(error) }, 500);
  }
});

async function transcribeWithGroq(file: File, offsetMs: number, apiKey: string): Promise<Segment[]> {
  const form = new FormData();
  form.append("file", file, file.name || "audio.ogg");
  form.append("model", GROQ_MODEL);
  form.append("language", "tr");
  form.append("response_format", "verbose_json");
  form.append("temperature", "0");
  form.append("timestamp_granularities[]", "segment");
  const response = await fetch(GROQ_URL, { method: "POST", headers: { Authorization: `Bearer ${apiKey}` }, body: form });
  const text = await response.text();
  if (!response.ok) throw new Error(`groq_${response.status}:${text.slice(0, 500)}`);
  const body = JSON.parse(text);
  const source = Array.isArray(body.segments) ? body.segments : [];
  return source.map((s: any) => ({
    id: 0,
    start_ms: offsetMs + Math.max(0, Math.round(Number(s.start || 0) * 1000)),
    end_ms: offsetMs + Math.max(1, Math.round(Number(s.end || 0) * 1000)),
    tr: cleanTurkish(String(s.text || "")),
  })).filter((s: Segment) => s.tr.length > 0 && s.end_ms > s.start_ms);
}

async function transcribeWithGemini(file: File, offsetMs: number, apiKey: string, displayName: string): Promise<Segment[]> {
  const uploaded = await uploadGeminiFile(file, apiKey, displayName);
  try {
    await waitForGeminiFile(uploaded.name, apiKey);
    const schema = {
      type: "object",
      properties: {
        segments: {
          type: "array",
          items: {
            type: "object",
            properties: { start_ms: { type: "integer" }, end_ms: { type: "integer" }, tr: { type: "string" } },
            required: ["start_ms", "end_ms", "tr"],
          },
        },
      },
      required: ["segments"],
    };
    const prompt = `Transcribe this Turkish movie audio accurately. Return subtitle-ready Turkish dialogue segments with start_ms and end_ms relative to THIS audio file only.\nRules:\n- Turkish transcription only; do not translate.\n- Preserve every intelligible spoken line, names, slang, particles and short replies.\n- Do not invent dialogue from music or sound effects.\n- Keep segments naturally sized for subtitles, normally 1-8 seconds.\n- start_ms/end_ms must follow the actual speech timing and be monotonic.\n- Return only the requested structured JSON.`;
    const result = await geminiInteraction(apiKey, {
      model: GEMINI_MODEL,
      input: [{ type: "text", text: prompt }, { type: "audio", uri: uploaded.uri, mime_type: uploaded.mimeType }],
      response_format: { type: "text", mime_type: "application/json", schema },
      generation_config: { thinking_level: "low", max_output_tokens: 65536 },
    });
    const source = JSON.parse(extractInteractionText(result))?.segments;
    return (Array.isArray(source) ? source : []).map((s: any) => ({
      id: 0,
      start_ms: offsetMs + Math.max(0, Math.round(Number(s.start_ms || 0))),
      end_ms: offsetMs + Math.max(1, Math.round(Number(s.end_ms || 0))),
      tr: cleanTurkish(String(s.tr || "")),
    })).filter((s: Segment) => s.tr.length > 0 && s.end_ms > s.start_ms);
  } finally {
    await deleteGeminiFile(uploaded.name, apiKey);
  }
}

async function translateMovieAsOneContext(segments: Segment[], apiKey: string): Promise<Array<{ id: number; ar: string }>> {
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
  const prompt = `You are the senior Arabic subtitle translator for a Turkish feature film. Read the ENTIRE ordered Turkish dialogue first so character relationships, names, pronouns, jokes, threats, idioms, callbacks and tone stay consistent across the whole movie.\n\nTranslate every segment into polished, natural Modern Standard Arabic that feels professionally subtitled, not machine-translated.\nRules:\n- Preserve the exact intended meaning and emotional tone.\n- Prefer natural cinematic Arabic over literal Turkish word order.\n- Preserve names and recurring terminology consistently.\n- Translate Turkish idioms by meaning, not word-for-word.\n- Keep concise subtitle phrasing without deleting information.\n- Never omit, merge, reorder, summarize, censor, explain or add dialogue.\n- Return every id exactly once and only the Arabic for that id.\n- Do not output timestamps; the server preserves the verified ASR timeline.\n\nFull movie dialogue:\n${JSON.stringify(segments.map(({ id, tr }) => ({ id, tr })))}`;
  const result = await geminiInteraction(apiKey, {
    model: GEMINI_MODEL,
    input: prompt,
    response_format: { type: "text", mime_type: "application/json", schema },
    generation_config: { thinking_level: "low", max_output_tokens: 65536 },
  });
  const source = JSON.parse(extractInteractionText(result))?.subtitles;
  return (Array.isArray(source) ? source : []).map((item: any) => ({ id: Number(item?.id), ar: cleanArabic(String(item?.ar || "")) }));
}

async function geminiInteraction(apiKey: string, body: unknown): Promise<any> {
  const response = await fetch(GEMINI_INTERACTIONS_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`gemini_${response.status}:${text.slice(0, 900)}`);
  const parsed = JSON.parse(text);
  if (parsed?.status && parsed.status !== "completed") throw new Error(`gemini_interaction_${parsed.status}`);
  return parsed;
}

function extractInteractionText(interaction: any): string {
  if (typeof interaction?.output_text === "string" && interaction.output_text.trim()) return interaction.output_text.trim();
  const steps = Array.isArray(interaction?.steps) ? interaction.steps : [];
  for (let i = steps.length - 1; i >= 0; i--) {
    const step = steps[i];
    if (step?.type !== "model_output" || !Array.isArray(step.content)) continue;
    const texts = step.content.filter((p: any) => p?.type === "text" && typeof p.text === "string").map((p: any) => p.text);
    if (texts.length) return texts.join("").trim();
  }
  throw new Error("gemini_empty_output");
}

async function uploadGeminiFile(file: File, apiKey: string, displayName: string): Promise<UploadedGeminiFile> {
  const start = await fetch(GEMINI_UPLOAD_URL, {
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
  });
  if (!start.ok) throw new Error(`gemini_upload_start_${start.status}:${(await start.text()).slice(0, 500)}`);
  const uploadUrl = start.headers.get("x-goog-upload-url");
  if (!uploadUrl) throw new Error("gemini_upload_url_missing");
  const upload = await fetch(uploadUrl, {
    method: "POST",
    headers: {
      "Content-Length": String(file.size),
      "X-Goog-Upload-Offset": "0",
      "X-Goog-Upload-Command": "upload, finalize",
      "Content-Type": file.type || "audio/ogg",
    },
    body: file,
  });
  const text = await upload.text();
  if (!upload.ok) throw new Error(`gemini_upload_${upload.status}:${text.slice(0, 500)}`);
  const info = JSON.parse(text)?.file;
  if (!info?.name || !info?.uri) throw new Error("gemini_file_invalid");
  return { name: String(info.name), uri: String(info.uri), mimeType: String(info.mimeType || info.mime_type || file.type || "audio/ogg") };
}

async function waitForGeminiFile(name: string, apiKey: string): Promise<void> {
  for (let attempt = 0; attempt < 24; attempt++) {
    const response = await fetch(`${GEMINI_API_BASE}/${name}`, { headers: { "x-goog-api-key": apiKey } });
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
  try { await fetch(`${GEMINI_API_BASE}/${name}`, { method: "DELETE", headers: { "x-goog-api-key": apiKey } }); } catch { /* best effort */ }
}

function normalizeTimeline(input: Segment[]): Segment[] {
  const output: Segment[] = [];
  let nextId = 0;
  for (const raw of input) {
    const tr = cleanTurkish(raw.tr);
    if (!tr) continue;
    let start = Math.max(0, Math.round(raw.start_ms));
    let end = Math.max(start + 120, Math.round(raw.end_ms));
    if (output.length) {
      const previous = output[output.length - 1];
      if (start < previous.start_ms) start = previous.start_ms;
      if (end <= start) end = start + 120;
    }
    output.push({ id: nextId++, start_ms: start, end_ms: end, tr });
  }
  return output;
}

function cleanTurkish(value: string): string { return value.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(); }
function cleanArabic(value: string): string { return value.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").replace(/,/g, "،").replace(/;/g, "؛").replace(/\?/g, "؟").trim(); }
function safeMs(value: FormDataEntryValue | null): number { const parsed = Number(value || 0); return Number.isFinite(parsed) && parsed >= 0 ? Math.round(parsed) : 0; }
function sleep(ms: number) { return new Promise((resolve) => setTimeout(resolve, ms)); }
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders(), "Content-Type": "application/json; charset=utf-8" } }); }
function corsHeaders() { return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" }; }
