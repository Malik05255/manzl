# AGENTS.md

This repository contains one standalone Android application: **MovieTranslator**.

## Source of truth

- Module: `translator-app`
- Package: `com.manzl.movietranslator`
- UI: Jetpack Compose, Arabic/RTL, one-tap flow
- Playback: AndroidX Media3 / ExoPlayer with external Arabic SRT over the original local video
- Primary processing: cloud-first; the movie itself never leaves the phone
- Audio preparation: FFmpegKit Audio, Opus mono 16 kHz at 24 kbps
- Turkish speech recognition: Groq `whisper-large-v3` for the primary part; Gemini audio fallback/routing when free-tier quota requires it
- Turkish → Arabic translation: Gemini Flash using the complete ordered movie transcript as one translation context
- Cloud gateway: Supabase Edge Function `movie-translate`; provider secret keys stay server-side
- Offline Whisper/SMaLL-100 code may remain temporarily only as a rollback/fallback while the cloud path is validated

Do not reintroduce the previous VibeApp/build-engine/plugin architecture. The product is intentionally a single-purpose movie subtitle translator.

## Build

```bash
./gradlew :translator-app:assembleDebug
./gradlew :translator-app:testDebugUnitTest
./gradlew :translator-app:lintDebug
```

## Product constraints

1. Never upload the user's video to a server. Only a temporary speech-optimized audio derivative may be uploaded.
2. Never embed Groq or Gemini secret API keys in the APK. Public Supabase publishable credentials are allowed; provider secrets belong in the backend environment.
3. Avoid transcoding/copying the video. Generate one Arabic SRT and attach it during Media3 playback.
4. Support movies up to 3 hours with at most two hidden audio parts: <=2h is one part; >2h and <=3h is exactly two parts.
5. Keep the user flow simple: choose video → translate → watch/export. Part routing and provider fallback must be automatic.
6. Optimize for the fixed benchmark: a two-minute Turkish clip targets <20 seconds end-to-end on a good connection, but never claim the target is achieved until measured on the physical device.
7. Translation quality is the primary acceptance gate: preserve every intelligible line and produce natural cinematic Arabic, using the full movie transcript as context rather than translating isolated fragments.
8. Keep a zero-bill/free-tier-aware routing strategy. If a provider quota is exhausted, use an allowed free fallback or fail clearly; never silently incur paid usage.
