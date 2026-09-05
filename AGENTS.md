# AGENTS.md

This repository contains one standalone Android application: **MovieTranslator**.

## Source of truth

- Module: `translator-app`
- Package: `com.manzl.movietranslator`
- UI: Jetpack Compose
- Playback: AndroidX Media3
- Turkish speech recognition: Vosk, local model downloaded on first use
- Turkish → Arabic translation: ML Kit Translation, on-device after model download
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
