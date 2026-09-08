export interface Env {
  MOVIES: R2Bucket;
  PUBLIC_BASE_URL: string;
  INGEST_TOKEN?: string;
}

type Retention = "1d" | "7d" | "30d" | "permanent";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response("ok", { headers: cors() });

    if (url.pathname === "/health") {
      return json({ ok: true, service: "h-ai-media-ingest" });
    }

    if (url.pathname === "/ingest" && request.method === "POST") {
      if (env.INGEST_TOKEN && request.headers.get("x-ingest-token") !== env.INGEST_TOKEN) {
        return json({ error: "unauthorized" }, 401);
      }
      const body = await request.json<any>();
      const sourceUrl = String(body?.source_url || "");
      const retention = normalizeRetention(body?.retention);
      if (!safeUrl(sourceUrl)) return json({ error: "invalid_source_url" }, 400);

      const source = await fetch(sourceUrl, {
        headers: request.headers.get("user-agent") ? { "User-Agent": request.headers.get("user-agent")! } : undefined,
      });
      if (!source.ok || !source.body) {
        return json({ error: "source_fetch_failed", status: source.status }, 502);
      }

      const filename = safeFilename(body?.filename || filenameFromUrl(sourceUrl));
      const id = crypto.randomUUID();
      const key = `${retentionPrefix(retention)}/${id}/${filename}`;
      const contentType = source.headers.get("content-type") || "application/octet-stream";

      // Stream source -> R2. The movie never passes through device storage or Worker memory.
      await env.MOVIES.put(key, source.body, {
        httpMetadata: { contentType },
        customMetadata: {
          source: new URL(sourceUrl).origin,
          retention,
          created_at: new Date().toISOString(),
        },
      });

      const base = (env.PUBLIC_BASE_URL || url.origin).replace(/\/$/, "");
      return json({
        status: "stored",
        key,
        retention,
        playback_url: `${base}/media/${encodeURIComponent(key)}`,
      });
    }

    if (url.pathname.startsWith("/media/") && request.method === "GET") {
      const key = decodeURIComponent(url.pathname.slice("/media/".length));
      const object = await env.MOVIES.get(key, {
        range: request.headers.get("range") || undefined,
      });
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

function normalizeRetention(value: unknown): Retention {
  const v = String(value || "7d");
  return v === "1d" || v === "30d" || v === "permanent" ? v : "7d";
}

function retentionPrefix(retention: Retention): string {
  if (retention === "1d") return "ttl/1d";
  if (retention === "30d") return "ttl/30d";
  if (retention === "permanent") return "permanent";
  return "ttl/7d";
}

function safeUrl(value: string): boolean {
  try {
    const u = new URL(value);
    if (u.protocol !== "https:" && u.protocol !== "http:") return false;
    const h = u.hostname.toLowerCase();
    if (!h || h === "localhost" || h.endsWith(".local")) return false;
    if (/^(127\.|10\.|192\.168\.|169\.254\.)/.test(h)) return false;
    if (/^172\.(1[6-9]|2\d|3[01])\./.test(h)) return false;
    return true;
  } catch {
    return false;
  }
}

function filenameFromUrl(value: string): string {
  try { return decodeURIComponent(new URL(value).pathname.split("/").filter(Boolean).pop() || "movie.mp4"); }
  catch { return "movie.mp4"; }
}

function safeFilename(value: unknown): string {
  const raw = String(value || "movie.mp4").replace(/[\\/:*?"<>|]+/g, "_").slice(0, 150);
  return raw || "movie.mp4";
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...cors(), "content-type": "application/json; charset=utf-8" } });
}

function cors(): Record<string,string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type,x-ingest-token,range,user-agent",
    "access-control-expose-headers": "content-range,content-length,etag,accept-ranges",
  };
}
