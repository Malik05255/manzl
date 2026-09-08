# Smart Media v1 (development branch)

This branch prototypes the next H AI architecture without changing `main`.

## Goals

- One simple gateway for Android and web.
- Direct movie URL path so the phone does not download/transcode the video.
- Automatic language detection and Arabic output.
- Quality-first provider routing with free-tier awareness.
- Optional retention policy metadata: none / 1 day / 7 days / 30 days / permanent.
- Reusable SRT + WebVTT + transcript + summary/characters/events.
- Provider telemetry so routing can become data-driven over time.
- Existing local-file translation path remains available as the fallback.

## Architecture

```text
Android / Web
    |
    v
Supabase media-gateway
    |-- Smart Router
    |-- Groq Whisper V3 / Turbo (ASR)
    |-- Azure Translator -> Groq translation fallback
    |-- Groq selective review
    |-- Groq transcript analysis
    |-- media_jobs + provider_route_stats
    |
    +--> original movie URL for zero-copy playback

Optional retained copy:
source URL -> Cloudflare Worker -> R2 -> player
```

## Direct URL algorithm

1. Validate the URL and retention policy.
2. Route ASR:
   - quality-sensitive/long/unknown language -> `whisper-large-v3`
   - shorter known-language content can use Turbo first
   - the other Whisper model is fallback.
3. Do not provide a source language to Whisper when the user chooses Auto.
4. Translate to Arabic:
   - Azure first when its free quota/key is available.
   - Groq GPT-OSS fallback.
5. Review only suspicious Arabic lines instead of paying/reprocessing the entire transcript.
6. Generate SRT and WebVTT.
7. Generate compact Arabic summary, characters, events, chapters and keywords.
8. Persist job/result metadata and provider trace when the new migration is deployed.

## Required Supabase secrets

Provider secrets stay server-side. Never place them in the Android APK or web JavaScript.

- `GROQ_API_KEY`
- `AZURE_TRANSLATOR_KEY` (optional fallback order changes automatically if absent)
- `AZURE_TRANSLATOR_REGION`
- `AZURE_TRANSLATOR_ENDPOINT` (optional)

Supabase-provided server credentials are used for the private job tables.

## R2 retention

`cloudflare/media-ingest` is prepared for a dedicated R2 bucket. It streams source -> R2 instead of buffering the movie.

Recommended lifecycle prefixes:

- `ttl/1d/` -> expire after 1 day
- `ttl/7d/` -> expire after 7 days
- `ttl/30d/` -> expire after 30 days
- `permanent/` -> no automatic deletion

The retention selector is already present in Android/web. On this development iteration, direct URL translation/playback works without requiring R2. The R2 copy path becomes active after the Worker/bucket is deployed and connected.

## Web

The static prototype is under `web/` and can be deployed to Cloudflare Pages. It uses the same `media-gateway` function and plays the original URL with generated WebVTT.

## Important limits

Supabase Edge Functions on Free have a 150-second wall-clock cap. The direct URL route is intentionally optimized around Groq's URL ASR because Groq processes audio far faster than realtime, but very slow origin servers can still exceed this cap. The long-term durable version should move remote ingestion/orchestration to Cloudflare Workflows/Durable Objects or another durable queue while leaving Supabase as the control plane.

## Acceptance before merging to main

- Android CI, tests and lint pass.
- Test a direct HTTPS MP4 on Honor 200.
- Verify Arabic subtitles stay synchronized.
- Verify a 2-hour movie does not make the phone perform local video transcoding on the URL path.
- Verify provider failure falls back without paid usage.
- Deploy migration/function/worker only after dev validation.
