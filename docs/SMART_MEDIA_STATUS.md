# H AI Smart Media — development status

Branch: `development-smart-media-v1`

## Implemented

- Android + web share one private Supabase `media-gateway`.
- Personal 256-bit sync key identifies the private library; only its SHA-256-derived account id is stored server-side.
- Direct movie URL input with Arabic as the fixed output language.
- Quality-first ASR routing between Groq Whisper Large V3 and Turbo.
- Large-film cloud audio extractor: stream URL -> FFmpeg -> mono 16 kHz Opus 24 kbps -> ~55 minute chunks -> Groq.
- Gemini video-file fallback is available when configured and the direct media fits the configured provider ceiling.
- Groq text translation first, Azure fallback, selective high-quality review, summary/characters/events.
- SRT + WebVTT + web playback track.
- Optional R2 retention: none / 1 day / 7 days / 30 days / permanent.
- Durable Cloudflare Workflow design so the phone/browser does not need to remain open.
- Provider telemetry for learned routing.
- Hard zero-bill gateway guard: default maximum 5 new cloud movie jobs per database day.

## Already deployed to the connected Supabase project

- `smart_media_gateway` migration.
- `smart_media_gateway_v3` migration.
- `smart_media_quota` migration.
- `media-gateway` Edge Function v2 with `verify_jwt=false` and explicit publishable-key validation.

## Still requires external account deployment

1. Cloudflare Worker/Workflow + R2 bucket `h-ai-movies`.
2. Worker secrets: Groq, Supabase secret/service key, orchestrator token; Gemini/Azure optional.
3. Free audio extractor host (Render blueprint included) and its Groq/token secrets.
4. Set `SMART_MEDIA_ORCHESTRATOR_URL` and `SMART_MEDIA_ORCHESTRATOR_TOKEN` in Supabase after the Worker has a public URL.
5. End-to-end test a short direct URL and a real ~2h movie before merging to `main`.

`main` remains intentionally unchanged until those tests pass.
