import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const GROQ_TRANSCRIBE_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
const GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
const GEMINI_INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
const GEMINI_MODEL = "gemini-3.8-flash";
const GROQ_ASR_MODEL = "whisper-large-v3";
const GROQ_ASR_TURBO = "whisper-large-v3-turbo";
const GROQ_REVIEW_MODEL = "openai/gpt-oss-120b";
const GROQ_TRANSLATE_MODEL = "openai/gpt-oss-120b";
const MAX_PART_BYTES = 24 * 1024 * 1024;
const API_REVISION = "2026-05-20";
const GROQ_DIRECT_MAX_SEGMENTS = 700;
const GROQ_DIRECT_MAX_CHARS = 60_000;

type Segment = { id: number; start_ms: number; end_ms: number; tr: string };
type Subtitle = { id: number; ar: string };

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  try {
    const contentType = req.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
      const body = await req.json();
      const mode = String(body?.mode || "");
      if (mode === "translate_start") return handleTranslateStart(body);
      if (mode === "review_chunk") return handleReviewChunk(body);
      if (mode === "poll") return handlePoll(body);
      return json({ error: "invalid_json_mode" }, 400);
    }

    const form = await req.formData();
    if (String(form.get("mode") || "") === "asr") return handleAsr(form);
    return json({ error: "invalid_multipart_mode" }, 400);
  } catch (error) {
    console.error("movie-translate", error);
    return json({
      error: "provider_temporarily_unavailable",
      message: "إحدى خدمات الترجمة تحت ضغط مؤقت. ستتم إعادة المحاولة تلقائيًا.",
    }, 503);
  }
});

async function handleAsr(form: FormData): Promise<Response> {
  const groqKey = Deno.env.get("GROQ_API_KEY");
  if (!groqKey) return json({ error: "groq_key_missing", message: "خدمة فهم الحوار غير مفعلة." }, 503);

  const audio = form.get("audio");
  if (!(audio instanceof File)) return json({ error: "audio_required" }, 400);
  if (audio.size <= 0 || audio.size > MAX_PART_BYTES) return json({ error: "audio_size_invalid" }, 413);

  const offsetMs = safeMs(form.get("offset_ms"));
  const requested = String(form.get("provider") || "groq").toLowerCase();
  const preferred = requested === "groq_turbo" ? GROQ_ASR_TURBO : GROQ_ASR_MODEL;
  const fallback = preferred === GROQ_ASR_MODEL ? GROQ_ASR_TURBO : GROQ_ASR_MODEL;
  const started = Date.now();

  for (const model of [preferred, fallback]) {
    try {
      const segments = normalizePart(await transcribeGroq(audio, offsetMs, groqKey, model));
      if (!segments.length) continue;
      return json({
        status: "completed",
        segments,
        metrics: { provider: model, asr_ms: Date.now() - started },
      });
    } catch (error) {
      console.warn(`ASR ${model} failed`, error instanceof Error ? error.message : String(error));
    }
  }

  return json({
    error: "asr_temporarily_unavailable",
    message: "خدمة فهم الحوار تحت ضغط مؤقت. ستتم إعادة المحاولة تلقائيًا.",
  }, 503);
}

async function handleTranslateStart(body: any): Promise<Response> {
  const segments = parseSegments(body?.segments);
  if (!segments.length) return json({ error: "segments_empty" }, 400);

  const azureKey = Deno.env.get("AZURE_TRANSLATOR_KEY");
  const azureRegion = Deno.env.get("AZURE_TRANSLATOR_REGION");
  if (azureKey && azureRegion) {
    try {
      const subtitles = await translateAzure(segments, azureKey, azureRegion);
      if (subtitles.length === segments.length) {
        return json({
          status: "completed",
          subtitles,
          metrics: { provider: "Azure Translator F0" },
        });
      }
    } catch (error) {
      console.warn("Azure translation fallback", error instanceof Error ? error.message : String(error));
    }
  }

  // For short and medium dialogue sets, Groq is a fast fallback that avoids
  // creating a fragile long-running Gemini background interaction.
  const groqKey = Deno.env.get("GROQ_API_KEY");
  if (groqKey && canUseGroqDirect(segments)) {
    try {
      const subtitles = await translateWithGroq(segments, groqKey);
      if (subtitles.length === segments.length) {
        return json({
          status: "completed",
          subtitles,
          metrics: { provider: `${GROQ_TRANSLATE_MODEL} • translate` },
        });
      }
    } catch (error) {
      console.warn("Groq direct translation fallback", error instanceof Error ? error.message : String(error));
    }
  }

  const geminiKey = Deno.env.get("GEMINI_API_KEY");
  if (!geminiKey) {
    return json({
      error: "translation_provider_missing",
      message: "خدمة الترجمة الأساسية غير متاحة مؤقتًا. ستتم إعادة المحاولة تلقائيًا.",
    }, 503);
  }

  try {
    const interaction = await startGeminiTranslation(segments, geminiKey);
    if (!interaction?.id) throw new Error("gemini_background_id_missing");
    if (String(interaction.status || "") === "completed") {
      const subtitles = parseGeminiSubtitles(interaction);
      if (subtitles.length !== segments.length) throw new Error("gemini_result_mismatch");
      return json({
        status: "completed",
        subtitles,
        metrics: { provider: GEMINI_MODEL },
      });
    }
    return json({
      status: "in_progress",
      job_id: String(interaction.id),
      provider: GEMINI_MODEL,
    });
  } catch (error) {
    console.warn("Gemini start unavailable", error instanceof Error ? error.message : String(error));
    return json({
      error: "translation_temporarily_unavailable",
      message: "خدمة الترجمة تحت ضغط مؤقت. ستتم إعادة المحاولة تلقائيًا.",
    }, 503);
  }
}

async function handleReviewChunk(body: any): Promise<Response> {
  const segments = parseSegments(body?.segments);
  const draft = parseDraft(body?.draft);
  const targetIds = Array.isArray(body?.target_ids)
    ? body.target_ids.map((v: any) => Number(v)).filter((v: number) => Number.isInteger(v))
    : [];
  if (!segments.length || !draft.size || !targetIds.length) {
    return json({ error: "review_input_invalid" }, 400);
  }

  const fallback = targetIds
    .map((id: number) => ({ id, ar: draft.get(id) || "" }))
    .filter((item: Subtitle) => item.ar.length > 0);

  const groqKey = Deno.env.get("GROQ_API_KEY");
  if (!groqKey) {
    return json({ status: "completed", subtitles: fallback, metrics: { provider: "review-skipped" } });
  }

  try {
    const reviewed = await reviewWithGroq(segments, draft, targetIds, groqKey);
    const merged = targetIds.map((id: number) => ({ id, ar: reviewed.get(id) || draft.get(id) || "" }))
      .filter((item: Subtitle) => item.ar.length > 0);
    return json({ status: "completed", subtitles: merged, metrics: { provider: GROQ_REVIEW_MODEL } });
  } catch (error) {
    console.warn("Groq review skipped", error instanceof Error ? error.message : String(error));
    return json({ status: "completed", subtitles: fallback, metrics: { provider: "review-skipped" } });
  }
}

async function handlePoll(body: any): Promise<Response> {
  const kind = String(body?.kind || "");
  const jobId = String(body?.job_id || "");
  if (kind !== "translate" || !jobId) return json({ error: "poll_request_invalid" }, 400);

  const geminiKey = Deno.env.get("GEMINI_API_KEY");
  if (!geminiKey) return json({ status: "failed", message: "تعذر استخدام المسار الاحتياطي حاليًا." });

  try {
    const interaction = await getGeminiInteraction(jobId, geminiKey);
    const status = String(interaction?.status || "completed");
    if (status === "queued" || status === "in_progress") {
      return json({ status: "in_progress", provider: GEMINI_MODEL, retry_after_ms: 4_000 });
    }
    if (status !== "completed") {
      return json({
        status: status || "failed",
        message: "تعذر إكمال المسار الاحتياطي. سيبدأ التطبيق محاولة جديدة تلقائيًا.",
        provider: GEMINI_MODEL,
      });
    }
    return json({
      status: "completed",
      subtitles: parseGeminiSubtitles(interaction),
      metrics: { provider: GEMINI_MODEL },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.warn("Gemini poll temporary failure", message);

    // 429/5xx/high-demand responses are temporary. Do NOT kill the saved job;
    // the same interaction may become readable a few seconds later.
    if (isTransientProviderError(message)) {
      return json({
        status: "in_progress",
        provider: `${GEMINI_MODEL} • waiting`,
        retry_after_ms: 8_000,
        message: "الخدمة تحت ضغط مؤقت. تتم إعادة المحاولة تلقائيًا.",
      });
    }

    return json({
      status: "failed",
      message: "تعذر إكمال المسار الاحتياطي. سيبدأ التطبيق محاولة جديدة تلقائيًا.",
      provider: GEMINI_MODEL,
    });
  }
}

async function transcribeGroq(file: File, offsetMs: number, apiKey: string, model: string): Promise<Segment[]> {
  const form = new FormData();
  form.append("file", file, file.name || "audio.ogg");
  form.append("model", model);
  form.append("language", "tr");
  form.append("response_format", "verbose_json");
  form.append("temperature", "0");
  form.append("timestamp_granularities[]", "segment");

  const response = await timedFetch(GROQ_TRANSCRIBE_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}` },
    body: form,
  }, 90_000, "groq_asr_timeout");
  const text = await response.text();
  if (!response.ok) throw new Error(`groq_${response.status}:${text.slice(0, 500)}`);
  const body = JSON.parse(text);
  const source = Array.isArray(body?.segments) ? body.segments : [];
  return source.map((s: any) => ({
    id: 0,
    start_ms: offsetMs + Math.max(0, Math.round(Number(s?.start || 0) * 1000)),
    end_ms: offsetMs + Math.max(1, Math.round(Number(s?.end || 0) * 1000)),
    tr: clean(String(s?.text || "")),
  })).filter((s: Segment) => s.tr.length > 0 && s.end_ms > s.start_ms);
}

async function translateAzure(segments: Segment[], key: string, region: string): Promise<Subtitle[]> {
  const endpoint = (Deno.env.get("AZURE_TRANSLATOR_ENDPOINT") || "https://api.cognitive.microsofttranslator.com").replace(/\/$/, "");
  const batches = makeBatches(segments, 80, 42_000);
  const output: Subtitle[] = [];

  for (const batch of batches) {
    const response = await timedFetch(`${endpoint}/translate?api-version=3.0&from=tr&to=ar`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json; charset=UTF-8",
        "Ocp-Apim-Subscription-Key": key,
        "Ocp-Apim-Subscription-Region": region,
      },
      body: JSON.stringify(batch.map((item) => ({ Text: item.tr }))),
    }, 22_000, "azure_translate_timeout");
    const text = await response.text();
    if (!response.ok) throw new Error(`azure_${response.status}:${text.slice(0, 600)}`);
    const result = JSON.parse(text);
    if (!Array.isArray(result) || result.length !== batch.length) throw new Error("azure_result_mismatch");
    batch.forEach((item, index) => {
      const ar = cleanArabic(String(result[index]?.translations?.[0]?.text || ""));
      if (!ar) throw new Error(`azure_empty_translation_${item.id}`);
      output.push({ id: item.id, ar });
    });
  }
  return output.sort((a, b) => a.id - b.id);
}

async function translateWithGroq(segments: Segment[], apiKey: string): Promise<Subtitle[]> {
  const output: Subtitle[] = [];
  const batches = makeBatches(segments, 70, 7_500);

  for (const batch of batches) {
    const prompt = `ترجم حوار الفيلم التركي التالي إلى العربية الفصحى الطبيعية المناسبة للترجمة السينمائية. حافظ على كل id مرة واحدة وبنفس الترتيب. لا تحذف ولا تدمج ولا تضف شرحًا. أعد JSON فقط بالشكل {"subtitles":[{"id":1,"ar":"..."}]}.\n\n${JSON.stringify(batch.map(({ id, tr }) => ({ id, tr })))}`;
    const response = await timedFetch(GROQ_CHAT_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: GROQ_TRANSLATE_MODEL,
        messages: [
          { role: "system", content: "You are a professional Turkish-to-Arabic subtitle translator. Output valid JSON only." },
          { role: "user", content: prompt },
        ],
        temperature: 0.1,
        reasoning_effort: "low",
        response_format: { type: "json_object" },
        max_completion_tokens: 7000,
      }),
    }, 38_000, "groq_translate_timeout");
    const text = await response.text();
    if (!response.ok) throw new Error(`groq_translate_${response.status}:${text.slice(0, 600)}`);
    const root = JSON.parse(text);
    const content = root?.choices?.[0]?.message?.content;
    if (typeof content !== "string" || !content.trim()) throw new Error("groq_translate_empty");
    const parsed = JSON.parse(content);
    const source = Array.isArray(parsed?.subtitles) ? parsed.subtitles : [];
    const map = new Map<number, string>();
    source.forEach((item: any) => {
      const id = Number(item?.id);
      const ar = cleanArabic(String(item?.ar || ""));
      if (batch.some((s) => s.id === id) && ar) map.set(id, ar);
    });
    for (const item of batch) {
      const ar = map.get(item.id);
      if (!ar) throw new Error(`groq_translate_missing_${item.id}`);
      output.push({ id: item.id, ar });
    }
  }

  return output.sort((a, b) => a.id - b.id);
}

async function reviewWithGroq(
  segments: Segment[],
  draft: Map<number, string>,
  targetIds: number[],
  apiKey: string,
): Promise<Map<number, string>> {
  const context = segments.map((s) => ({ id: s.id, tr: s.tr, ar: draft.get(s.id) || "" }));
  const prompt = `راجع ترجمة حوار فيلم تركي إلى العربية الفصحى. لا تعِد ترجمة كل شيء. عدّل فقط target_ids عند وجود خطأ دلالي أو ضمير أو اسم أو تعبير تركي حرفي أو صياغة عربية غير طبيعية. حافظ على الاختصار السينمائي ولا تضف شرحًا. أعد JSON فقط بالشكل {"subtitles":[{"id":1,"ar":"..."}]}. يجب أن يظهر كل target_id مرة واحدة.\n\ntarget_ids=${JSON.stringify(targetIds)}\ncontext=${JSON.stringify(context)}`;

  const response = await timedFetch(GROQ_CHAT_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: GROQ_REVIEW_MODEL,
      messages: [
        { role: "system", content: "You are a senior Turkish-to-Arabic subtitle editor. Output valid JSON only." },
        { role: "user", content: prompt },
      ],
      temperature: 0.1,
      reasoning_effort: "low",
      response_format: { type: "json_object" },
      max_completion_tokens: 7000,
    }),
  }, 38_000, "groq_review_timeout");
  const text = await response.text();
  if (!response.ok) throw new Error(`groq_review_${response.status}:${text.slice(0, 600)}`);
  const root = JSON.parse(text);
  const content = root?.choices?.[0]?.message?.content;
  if (typeof content !== "string" || !content.trim()) throw new Error("groq_review_empty");
  const parsed = JSON.parse(content);
  const source = Array.isArray(parsed?.subtitles) ? parsed.subtitles : [];
  const result = new Map<number, string>();
  source.forEach((item: any) => {
    const id = Number(item?.id);
    const ar = cleanArabic(String(item?.ar || ""));
    if (targetIds.includes(id) && ar) result.set(id, ar);
  });
  return result;
}

async function startGeminiTranslation(segments: Segment[], apiKey: string): Promise<any> {
  const schema = subtitleSchema();
  const prompt = `Translate this complete ordered Turkish movie dialogue into polished natural Modern Standard Arabic subtitles. Preserve every id exactly once. Preserve names, relationships, pronouns, tone and idioms. Do not merge, omit, explain or add dialogue. Return only the requested JSON.\n\n${JSON.stringify(segments.map(({ id, tr }) => ({ id, tr })))}`;
  return createGeminiInteraction(apiKey, {
    model: GEMINI_MODEL,
    input: prompt,
    response_format: { type: "text", mime_type: "application/json", schema },
    generation_config: { thinking_level: "low", max_output_tokens: 65536 },
    background: true,
    store: true,
  });
}

async function createGeminiInteraction(apiKey: string, body: unknown): Promise<any> {
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
  if (!response.ok) throw new Error(`gemini_${response.status}:${text.slice(0, 700)}`);
  return JSON.parse(text);
}

async function getGeminiInteraction(id: string, apiKey: string): Promise<any> {
  const response = await timedFetch(`${GEMINI_INTERACTIONS_URL}/${encodeURIComponent(id)}`, {
    headers: { "x-goog-api-key": apiKey, "Api-Revision": API_REVISION },
  }, 20_000, "gemini_poll_timeout");
  const text = await response.text();
  if (!response.ok) throw new Error(`gemini_poll_${response.status}:${text.slice(0, 500)}`);
  return JSON.parse(text);
}

function parseGeminiSubtitles(interaction: any): Subtitle[] {
  const parsed = JSON.parse(extractInteractionText(interaction));
  const source = Array.isArray(parsed?.subtitles) ? parsed.subtitles : [];
  return source.map((item: any) => ({
    id: Number(item?.id),
    ar: cleanArabic(String(item?.ar || "")),
  })).filter((item: Subtitle) => Number.isInteger(item.id) && item.ar.length > 0);
}

function extractInteractionText(interaction: any): string {
  if (typeof interaction?.output_text === "string" && interaction.output_text.trim()) return interaction.output_text.trim();
  const steps = Array.isArray(interaction?.steps) ? interaction.steps : [];
  for (let i = steps.length - 1; i >= 0; i--) {
    const step = steps[i];
    if (step?.type !== "model_output" || !Array.isArray(step.content)) continue;
    const text = step.content
      .filter((part: any) => part?.type === "text" && typeof part.text === "string")
      .map((part: any) => part.text)
      .join("")
      .trim();
    if (text) return text;
  }
  throw new Error("gemini_empty_output");
}

function parseSegments(source: any): Segment[] {
  if (!Array.isArray(source) || source.length > 20_000) return [];
  return source.map((item: any, index: number) => ({
    id: Number.isInteger(Number(item?.id)) ? Number(item.id) : index,
    start_ms: Math.max(0, Math.round(Number(item?.start_ms || 0))),
    end_ms: Math.max(1, Math.round(Number(item?.end_ms || 1))),
    tr: clean(String(item?.tr || "")),
  })).filter((item: Segment) => item.tr.length > 0 && item.end_ms > item.start_ms);
}

function parseDraft(source: any): Map<number, string> {
  const result = new Map<number, string>();
  if (!Array.isArray(source)) return result;
  source.forEach((item: any) => {
    const id = Number(item?.id);
    const ar = cleanArabic(String(item?.ar || ""));
    if (Number.isInteger(id) && ar) result.set(id, ar);
  });
  return result;
}

function makeBatches(segments: Segment[], maxItems: number, maxChars: number): Segment[][] {
  const output: Segment[][] = [];
  let current: Segment[] = [];
  let chars = 0;
  for (const segment of segments) {
    if (current.length && (current.length >= maxItems || chars + segment.tr.length > maxChars)) {
      output.push(current);
      current = [];
      chars = 0;
    }
    current.push(segment);
    chars += segment.tr.length;
  }
  if (current.length) output.push(current);
  return output;
}

function canUseGroqDirect(segments: Segment[]): boolean {
  if (segments.length > GROQ_DIRECT_MAX_SEGMENTS) return false;
  const chars = segments.reduce((sum, segment) => sum + segment.tr.length, 0);
  return chars <= GROQ_DIRECT_MAX_CHARS;
}

function isTransientProviderError(message: string): boolean {
  const lower = message.toLowerCase();
  return lower.includes("gemini_poll_429") ||
    lower.includes("gemini_poll_500") ||
    lower.includes("gemini_poll_502") ||
    lower.includes("gemini_poll_503") ||
    lower.includes("gemini_poll_504") ||
    lower.includes("high demand") ||
    lower.includes("overload") ||
    lower.includes("temporar") ||
    lower.includes("timeout");
}

function subtitleSchema() {
  return {
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
}

function normalizePart(input: Segment[]): Segment[] {
  return input
    .sort((a, b) => a.start_ms - b.start_ms)
    .map((raw, index) => ({
      id: index,
      start_ms: Math.max(0, Math.round(raw.start_ms)),
      end_ms: Math.max(Math.round(raw.start_ms) + 120, Math.round(raw.end_ms)),
      tr: clean(raw.tr),
    }))
    .filter((item) => item.tr.length > 0);
}

function clean(value: string): string {
  return value.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function cleanArabic(value: string): string {
  return clean(value).replace(/,/g, "،").replace(/;/g, "؛").replace(/\?/g, "؟");
}

function safeMs(value: FormDataEntryValue | string | null): number {
  const parsed = Number(value || 0);
  return Number.isFinite(parsed) && parsed >= 0 ? Math.round(parsed) : 0;
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

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...corsHeaders(),
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
  };
}
