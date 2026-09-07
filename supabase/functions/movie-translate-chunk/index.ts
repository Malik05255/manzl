import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
const GROQ_TRANSLATE_MODEL = "openai/gpt-oss-120b";
const MAX_SEGMENTS = 20_000;
const MAX_TARGET_IDS = 60;

type Segment = { id: number; start_ms: number; end_ms: number; tr: string };
type Subtitle = { id: number; ar: string };

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  try {
    const body = await req.json();
    if (String(body?.mode || "") !== "translate_chunk") {
      return json({ error: "invalid_mode" }, 400);
    }

    const segments = parseSegments(body?.segments);
    const targetIds = parseTargetIds(body?.target_ids);
    if (!segments.length || !targetIds.length) {
      return json({ error: "translation_chunk_invalid" }, 400);
    }

    const targetSet = new Set(targetIds);
    const targets = segments.filter((item) => targetSet.has(item.id));
    if (targets.length !== targetIds.length) {
      return json({ error: "translation_chunk_targets_missing" }, 400);
    }

    const groqKey = Deno.env.get("GROQ_API_KEY");
    if (groqKey) {
      try {
        const subtitles = await translateChunkWithGroq(segments, targets, groqKey);
        if (subtitles.length === targets.length) {
          return json({
            status: "completed",
            subtitles,
            metrics: { provider: `${GROQ_TRANSLATE_MODEL} • resumable-full-context` },
          });
        }
      } catch (error) {
        console.warn("resumable Groq translation failed", error instanceof Error ? error.message : String(error));
      }
    }

    const azureKey = Deno.env.get("AZURE_TRANSLATOR_KEY");
    const azureRegion = Deno.env.get("AZURE_TRANSLATOR_REGION");
    if (azureKey && azureRegion) {
      try {
        const subtitles = await translateAzure(targets, azureKey, azureRegion);
        if (subtitles.length === targets.length) {
          return json({
            status: "completed",
            subtitles,
            metrics: { provider: "Azure Translator F0 • resumable" },
          });
        }
      } catch (error) {
        console.warn("resumable Azure translation failed", error instanceof Error ? error.message : String(error));
      }
    }

    return json({
      error: "translation_chunk_temporarily_unavailable",
      message: "خدمة الترجمة تحت ضغط مؤقت. ستتم إعادة المحاولة من نفس النقطة تلقائيًا.",
    }, 503);
  } catch (error) {
    console.error("movie-translate-chunk", error);
    return json({
      error: "translation_chunk_temporarily_unavailable",
      message: "تعذر إكمال دفعة الترجمة مؤقتًا. ستتم إعادة المحاولة من نفس النقطة تلقائيًا.",
    }, 503);
  }
});

async function translateChunkWithGroq(
  allSegments: Segment[],
  targets: Segment[],
  apiKey: string,
): Promise<Subtitle[]> {
  const targetIds = targets.map((item) => item.id);
  const transcript = allSegments.map(({ id, tr }) => `${id}\t${tr}`).join("\n");
  const prompt = `أنت مترجم أفلام محترف من التركية إلى العربية الفصحى الطبيعية.\nلدينا النص الكامل المرتب للفيلم أدناه حتى تحافظ على الأسماء والعلاقات والضمائر والسياق والنبرة.\nترجم فقط الأسطر ذات target_ids. لا تحذف أي target_id ولا تدمج الأسطر ولا تضف شرحًا. حافظ على الاختصار المناسب للترجمة السينمائية.\nأعد JSON فقط بالشكل {"subtitles":[{"id":1,"ar":"..."}]}.\n\ntarget_ids=${JSON.stringify(targetIds)}\n\nFULL_TRANSCRIPT:\n${transcript}`;

  const response = await timedFetch(GROQ_CHAT_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: GROQ_TRANSLATE_MODEL,
      messages: [
        {
          role: "system",
          content: "You are a senior Turkish-to-Arabic cinematic subtitle translator. Use the complete transcript as context and output valid JSON only.",
        },
        { role: "user", content: prompt },
      ],
      temperature: 0.1,
      reasoning_effort: "low",
      response_format: { type: "json_object" },
      max_completion_tokens: 7000,
    }),
  }, 45_000, "groq_resumable_translate_timeout");

  const text = await response.text();
  if (!response.ok) throw new Error(`groq_resumable_${response.status}:${text.slice(0, 700)}`);
  const root = JSON.parse(text);
  const content = root?.choices?.[0]?.message?.content;
  if (typeof content !== "string" || !content.trim()) throw new Error("groq_resumable_empty");

  const parsed = JSON.parse(content);
  const source = Array.isArray(parsed?.subtitles) ? parsed.subtitles : [];
  const targetSet = new Set(targetIds);
  const map = new Map<number, string>();
  source.forEach((item: any) => {
    const id = Number(item?.id);
    const ar = cleanArabic(String(item?.ar || ""));
    if (targetSet.has(id) && ar) map.set(id, ar);
  });

  return targets.map((item) => {
    const ar = map.get(item.id);
    if (!ar) throw new Error(`groq_resumable_missing_${item.id}`);
    return { id: item.id, ar };
  });
}

async function translateAzure(segments: Segment[], key: string, region: string): Promise<Subtitle[]> {
  const endpoint = (Deno.env.get("AZURE_TRANSLATOR_ENDPOINT") || "https://api.cognitive.microsofttranslator.com").replace(/\/$/, "");
  const response = await timedFetch(`${endpoint}/translate?api-version=3.0&from=tr&to=ar`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json; charset=UTF-8",
      "Ocp-Apim-Subscription-Key": key,
      "Ocp-Apim-Subscription-Region": region,
    },
    body: JSON.stringify(segments.map((item) => ({ Text: item.tr }))),
  }, 25_000, "azure_resumable_translate_timeout");

  const text = await response.text();
  if (!response.ok) throw new Error(`azure_resumable_${response.status}:${text.slice(0, 700)}`);
  const result = JSON.parse(text);
  if (!Array.isArray(result) || result.length !== segments.length) throw new Error("azure_resumable_result_mismatch");

  return segments.map((item, index) => {
    const ar = cleanArabic(String(result[index]?.translations?.[0]?.text || ""));
    if (!ar) throw new Error(`azure_resumable_empty_${item.id}`);
    return { id: item.id, ar };
  });
}

function parseSegments(source: any): Segment[] {
  if (!Array.isArray(source) || source.length > MAX_SEGMENTS) return [];
  return source.map((item: any, index: number) => ({
    id: Number.isInteger(Number(item?.id)) ? Number(item.id) : index,
    start_ms: Math.max(0, Math.round(Number(item?.start_ms || 0))),
    end_ms: Math.max(1, Math.round(Number(item?.end_ms || 1))),
    tr: clean(String(item?.tr || "")),
  })).filter((item: Segment) => item.tr.length > 0 && item.end_ms > item.start_ms);
}

function parseTargetIds(source: any): number[] {
  if (!Array.isArray(source)) return [];
  return source
    .map((value: any) => Number(value))
    .filter((value: number) => Number.isInteger(value) && value >= 0)
    .filter((value: number, index: number, all: number[]) => all.indexOf(value) === index)
    .slice(0, MAX_TARGET_IDS);
}

function clean(value: string): string {
  return value.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function cleanArabic(value: string): string {
  return clean(value).replace(/,/g, "،").replace(/;/g, "؛").replace(/\?/g, "؟");
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
