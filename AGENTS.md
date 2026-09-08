# AGENTS.md

This repository contains one standalone Android application: **MovieTranslator / H AI Smart Media**.

## Current development source of truth

- Stable production branch: `main` — do not merge experimental Smart Media work without device/end-to-end validation.
- Active experimental branch: `development-smart-media-v1`.
- Android module: `translator-app`.
- Package: `com.manzl.movietranslator`.
- Android UI: Jetpack Compose, Arabic/RTL.
- Direct-link cloud gateway: Supabase Edge Function `media-gateway`.
- Long-running orchestration: Cloudflare Workflow `h-ai-smart-media-v1`.
- Optional temporary movie storage: Cloudflare R2 bucket `h-ai-movies`.
- Speech recognition: quality-first Smart Router across Groq Whisper Large V3 / Turbo, with a configurable server-side extractor path and Gemini File API fallback for large supported videos.
- Arabic translation: free-first Groq text model with Azure Translator as reliability/quota fallback.
- Quality review: selectively review suspicious lines instead of translating the whole movie twice.
- Results: Arabic SRT + WebVTT + transcript + summary + characters/events + direct playback/subtitle URLs when available.
- Provider secrets must remain server-side. Never embed Groq, Gemini, Azure, Supabase service-role, Cloudflare, ingest, or orchestrator secrets in the APK or static web files.

Do not reintroduce the previous VibeApp/build-engine/plugin architecture. The product is intentionally a focused personal movie translation/player system.

## Two source modes

### Direct URL

Preferred route when the user has a legitimate direct HTTP/HTTPS media URL:

1. Android/web sends only the URL and retention choice to `media-gateway`.
2. A durable cloud job is created and continues independently of the phone/browser.
3. The server probes the remote media and selects an ASR route according to duration, language, actual provider history, size, and configured free providers.
4. If retention is `none`, playback keeps using the original source URL whenever possible.
5. If retention is `1d`, `7d`, `30d`, or `permanent`, the system may keep a private R2 copy for playback. Expired temporary objects must not remain indefinitely.
6. Final playback is the movie URL plus an external Arabic subtitle track; do not re-encode the finished video merely to burn in subtitles.

### Local file

The proven local path may still prepare a speech-optimized audio derivative and attach the generated SRT to the original local movie. A future explicit cloud-retention upload is allowed only when the user chose a retention option other than `none`; it must use resumable/multipart streaming and must not silently upload a full local movie.

## Product constraints

1. **Quality is the hard gate.** Preserve every intelligible spoken line, timestamps, names, and context. Prefer stronger ASR when language is unknown, audio is long/difficult, or prior telemetry shows the fast route is less reliable.
2. **Zero-bill by default.** Routing must stay inside configured free allowances or fail clearly. Never silently enable a paid provider/service tier.
3. **Minimize phone impact.** Direct-URL jobs are server-side and must continue if the Android process/browser is closed. For local files, avoid unnecessary video transcoding/copying.
4. **Automatic Arabic target.** Source language can be auto-detected; English, Turkish, Japanese, Hindi and other supported languages should all route to Arabic without a per-language UI workflow.
5. **Provider abstraction.** Android/web call the H AI gateway, not individual provider APIs. Provider/model changes should normally be backend-only.
6. **Retention is explicit.** `none`, `1d`, `7d`, `30d`, `permanent`. The user chooses before processing. Never infer permanent retention.
7. **Player is URL + subtitle.** Keep WebVTT/SRT external whenever possible so a translated movie is ready without a costly final render.
8. **Adaptive learning is telemetry, not uncontrolled model training.** Store provider success, latency and quality evidence by language/duration bucket, plus future glossary/correction memory. Do not claim learning improves quality until measured.
9. **Do not bypass DRM, authentication, paywalls, or access controls.** Direct-link mode only handles URLs the user is authorized to access and that the backend can fetch normally.
10. **Never claim performance targets as achieved without measurements.** CI success proves build/static validation only; a real short URL and real ~2h movie must be tested end-to-end before production merge.

## Cloud design notes

- Cloudflare Workflow step outputs should remain small; full transcripts/subtitles/results belong in Supabase/R2, not Workflow state.
- Free Workers/Workflows have tight active CPU limits. Keep Workflow code primarily orchestration/network I/O and checkpoint large results externally.
- Large-media ASR must not assume a full multi-GB video fits Groq's free direct-file limit. Use URL/provider support when valid, otherwise server-side audio extraction/chunking or the configured Gemini large-video fallback.
- R2 multipart upload is the intended future mechanism for explicit local-file retention because it handles files larger than a single Worker request-body limit.

## Build / validation

```bash
./gradlew :translator-app:assembleDebug
./gradlew :translator-app:testDebugUnitTest
./gradlew :translator-app:lintDebug

deno check supabase/functions/_shared/smart-media-router.ts
deno check supabase/functions/media-gateway/index.ts
deno test supabase/functions/_shared/smart-media-router.test.ts

cd cloudflare/smart-media-orchestrator
npx wrangler@latest deploy --dry-run
```
