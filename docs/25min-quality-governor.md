# 25-minute human-quality governor

The local movie translator treats 25 minutes for a two-hour movie as a processing target after one-time model provisioning.

Core rules:
- Vosk remains the fast whole-film Turkish ASR pass.
- Quantized Whisper repairs only the worst-confidence clips and is wall-clock bounded.
- Qwen remains the preferred direct Turkish-to-Arabic translator, with recent scene context and `/no_think`.
- Translation batches expand adaptively if measured throughput predicts a deadline miss.
- A cheap quality gate retries suspicious Arabic only when enough time remains.
- ML Kit is an emergency/device fallback, not the preferred translation path.
- Models are not held in memory concurrently across heavy stages.
- The UI reports elapsed time, target time, progress and ETA.

The target is a governor objective, not a hardware guarantee; thermal throttling and unusually dense dialogue can still extend runtime.
