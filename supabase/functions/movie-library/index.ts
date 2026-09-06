import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.57.4";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) return json({ error: "server_not_configured" }, 503);

  try {
    const body = await req.json();
    const mode = String(body?.mode || "");
    const deviceHash = normalizeDeviceHash(body?.device_hash);
    if (!deviceHash) return json({ error: "invalid_device" }, 400);
    if (!(await isAuthorized(deviceHash))) {
      return json({ error: "device_not_authorized", message: "هذا الجهاز غير مرتبط بسحابة الترجمة." }, 403);
    }

    switch (mode) {
      case "library_list": return await listMovies(deviceHash);
      case "library_upsert_path": return await upsertPath(deviceHash, body);
      case "library_save_translation": return await saveTranslation(deviceHash, body);
      case "library_delete_translation": return await deleteTranslation(deviceHash, body);
      case "platform_status": return await platformStatus(deviceHash);
      case "usage_record": return await recordUsage(deviceHash, body);
      default: return json({ error: "invalid_mode" }, 400);
    }
  } catch (error) {
    console.error("movie-library", error);
    return json({ error: "internal_error", message: error instanceof Error ? error.message : String(error) }, 500);
  }
});

function db() {
  return createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { persistSession: false, autoRefreshToken: false } });
}

function normalizeDeviceHash(value: unknown): string | null {
  const text = String(value || "").trim().toLowerCase();
  return /^[a-f0-9]{64}$/.test(text) ? text : null;
}

async function isAuthorized(deviceHash: string): Promise<boolean> {
  const { data, error } = await db().from("movie_translator_state").select("owner_hash").eq("singleton", true).maybeSingle();
  if (error) throw error;
  return Boolean(data && data.owner_hash === deviceHash);
}

async function listMovies(deviceHash: string): Promise<Response> {
  const { data, error } = await db()
    .from("movie_translator_library")
    .select("movie_key,movie_name,video_uri,duration_ms,srt_text,cue_count,processing_ms,updated_at")
    .eq("device_hash", deviceHash)
    .order("updated_at", { ascending: false });
  if (error) throw error;
  return json({ movies: data || [] });
}

async function upsertPath(deviceHash: string, body: any): Promise<Response> {
  const movieKey = cleanKey(body?.movie_key);
  const movieName = cleanText(body?.movie_name, 240);
  const videoUri = cleanText(body?.video_uri, 3000);
  const durationMs = Math.max(0, Math.round(Number(body?.duration_ms || 0)));
  if (!movieKey || !movieName || !videoUri) return json({ error: "movie_input_invalid" }, 400);
  const client = db();
  const { data: existing, error: findError } = await client
    .from("movie_translator_library")
    .select("id")
    .eq("device_hash", deviceHash)
    .eq("movie_key", movieKey)
    .maybeSingle();
  if (findError) throw findError;
  const row = { device_hash: deviceHash, movie_key: movieKey, movie_name: movieName, video_uri: videoUri, duration_ms: durationMs, updated_at: new Date().toISOString() };
  const op = existing
    ? client.from("movie_translator_library").update(row).eq("id", existing.id)
    : client.from("movie_translator_library").insert(row);
  const { error } = await op;
  if (error) throw error;
  return json({ ok: true });
}

async function saveTranslation(deviceHash: string, body: any): Promise<Response> {
  const movieKey = cleanKey(body?.movie_key);
  const movieName = cleanText(body?.movie_name, 240);
  const videoUri = cleanText(body?.video_uri, 3000);
  const durationMs = Math.max(0, Math.round(Number(body?.duration_ms || 0)));
  const srtText = String(body?.srt_text || "");
  const cueCount = Math.max(0, Math.round(Number(body?.cue_count || 0)));
  const processingMs = Math.max(0, Math.round(Number(body?.processing_ms || 0)));
  if (!movieKey || !movieName || !srtText || srtText.length > 4_000_000) return json({ error: "translation_input_invalid" }, 400);
  const client = db();
  const { data: existing, error: findError } = await client
    .from("movie_translator_library")
    .select("id")
    .eq("device_hash", deviceHash)
    .eq("movie_key", movieKey)
    .maybeSingle();
  if (findError) throw findError;
  const row = {
    device_hash: deviceHash,
    movie_key: movieKey,
    movie_name: movieName,
    video_uri: videoUri || null,
    duration_ms: durationMs,
    srt_text: srtText,
    cue_count: cueCount,
    processing_ms: processingMs,
    updated_at: new Date().toISOString(),
  };
  const op = existing
    ? client.from("movie_translator_library").update(row).eq("id", existing.id)
    : client.from("movie_translator_library").insert(row);
  const { error } = await op;
  if (error) throw error;
  return json({ ok: true });
}

async function deleteTranslation(deviceHash: string, body: any): Promise<Response> {
  const movieKey = cleanKey(body?.movie_key);
  if (!movieKey) return json({ error: "movie_key_invalid" }, 400);
  const { error } = await db()
    .from("movie_translator_library")
    .update({ srt_text: null, cue_count: 0, processing_ms: 0, updated_at: new Date().toISOString() })
    .eq("device_hash", deviceHash)
    .eq("movie_key", movieKey);
  if (error) throw error;
  return json({ ok: true });
}

async function recordUsage(deviceHash: string, body: any): Promise<Response> {
  const today = new Date().toISOString().slice(0, 10);
  const groqSeconds = clampInt(body?.groq_audio_seconds, 0, 86400);
  const geminiSeconds = clampInt(body?.gemini_audio_seconds, 0, 86400);
  const geminiRequests = clampInt(body?.gemini_requests, 0, 1000);
  const client = db();
  const { data: existing, error: findError } = await client
    .from("movie_translator_usage")
    .select("usage_day,groq_audio_seconds,gemini_audio_seconds,gemini_requests")
    .eq("device_hash", deviceHash)
    .maybeSingle();
  if (findError) throw findError;
  const sameDay = existing?.usage_day === today;
  const row = {
    device_hash: deviceHash,
    usage_day: today,
    groq_audio_seconds: (sameDay ? Number(existing?.groq_audio_seconds || 0) : 0) + groqSeconds,
    gemini_audio_seconds: (sameDay ? Number(existing?.gemini_audio_seconds || 0) : 0) + geminiSeconds,
    gemini_requests: (sameDay ? Number(existing?.gemini_requests || 0) : 0) + geminiRequests,
    updated_at: new Date().toISOString(),
  };
  const { error } = await client.from("movie_translator_usage").upsert(row, { onConflict: "device_hash" });
  if (error) throw error;
  return json({ ok: true });
}

async function platformStatus(deviceHash: string): Promise<Response> {
  const today = new Date().toISOString().slice(0, 10);
  const { data, error } = await db()
    .from("movie_translator_usage")
    .select("usage_day,groq_audio_seconds,gemini_audio_seconds,gemini_requests")
    .eq("device_hash", deviceHash)
    .maybeSingle();
  if (error) throw error;
  const sameDay = data?.usage_day === today;
  const groqUsed = sameDay ? Number(data?.groq_audio_seconds || 0) : 0;
  const geminiAudioUsed = sameDay ? Number(data?.gemini_audio_seconds || 0) : 0;
  const geminiRequestsUsed = sameDay ? Number(data?.gemini_requests || 0) : 0;

  const GROQ_SOFT_SECONDS = 21600;
  const GEMINI_SOFT_AUDIO_SECONDS = 21600;
  const GEMINI_SOFT_REQUESTS = 100;
  const groqRemaining = percentRemaining(groqUsed, GROQ_SOFT_SECONDS);
  const geminiAudioRemaining = percentRemaining(geminiAudioUsed, GEMINI_SOFT_AUDIO_SECONDS);
  const geminiReqRemaining = percentRemaining(geminiRequestsUsed, GEMINI_SOFT_REQUESTS);
  const resetAt = nextUtcMidnightMs();

  return json({
    platforms: [
      {
        id: "groq",
        title: "Groq • Whisper Large V3",
        remaining_percent: groqRemaining,
        reset_at_ms: resetAt,
        detail: "تقدير استخدام التطبيق اليوم • الحد الفعلي يعتمد على حساب Groq",
      },
      {
        id: "gemini",
        title: "Google Gemini",
        remaining_percent: Math.min(geminiAudioRemaining, geminiReqRemaining),
        reset_at_ms: resetAt,
        detail: "ترجمة + مراجعة + احتياط صوتي • تقدير داخل التطبيق",
      },
    ],
  });
}

function cleanKey(value: unknown): string {
  const text = String(value || "").trim().toLowerCase();
  return /^[a-f0-9]{16,64}$/.test(text) ? text : "";
}
function cleanText(value: unknown, max: number): string { return String(value || "").trim().slice(0, max); }
function clampInt(value: unknown, min: number, max: number): number {
  const n = Math.round(Number(value || 0));
  return Number.isFinite(n) ? Math.max(min, Math.min(max, n)) : min;
}
function percentRemaining(used: number, limit: number): number {
  if (limit <= 0) return 100;
  return Math.max(0, Math.min(100, Math.round((1 - used / limit) * 100)));
}
function nextUtcMidnightMs(): number {
  const now = new Date();
  return Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1, 0, 0, 0, 0);
}
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders(), "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}
function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
  };
}
