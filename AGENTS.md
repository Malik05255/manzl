# AGENTS.md

This repository contains one standalone Android application: **MovieTranslator**.

## Source of truth

- Module: `translator-app`
- Package: `com.manzl.movietranslator`
- UI: Jetpack Compose
- Playback: AndroidX Media3
- Speech gating: Silero VAD
- Turkish speech recognition: local Whisper Base Q5_1 fast pass with selective Whisper Small Q5_1 repair
- Turkish → Arabic translation: local int8 SMaLL-100 through ONNX Runtime; semantic Turkish dialogue units are translated directly to Arabic
- Movie-domain adaptation pipeline: `training/` prepares Turkish/Arabic subtitle bitext, fine-tunes SMaLL-100, benchmarks it, and exports the two-file int8 ONNX runtime used by Android
- Audio decoding: Android `MediaExtractor` + `MediaCodec`

Do not reintroduce the previous VibeApp/build-engine/plugin architecture. The product is intentionally a single-purpose movie subtitle translator.

## Build

```bash
./gradlew :translator-app:assembleDebug
./gradlew :translator-app:testDebugUnitTest
./gradlew :translator-app:lintDebug
```

## Product constraints

1. Never upload the user's movie to a server.
2. Avoid transcoding/copying the video; work on the audio stream and attach generated SRT during playback.
3. Keep Turkish speech recognition and Turkish→Arabic translation usable offline after their first model downloads.
4. Keep the UI Arabic/RTL and focused on one flow: choose video → translate → watch/export.
5. Optimize for the acceptance benchmark: a two-minute Turkish clip should approach 30 seconds end-to-end on the target phone while preserving dialogue coverage and natural Arabic meaning.
6. Do not trade semantic translation quality for cosmetic Arabic output. Benchmark against held-out human subtitle references before changing the default translation model.
