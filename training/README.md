# Manzl Movie TR→AR v1

This directory is the reproducible domain-adaptation pipeline for the Android translator.
The goal is not generic Turkish→Arabic MT; it is **natural Arabic movie subtitles from Turkish dialogue** while preserving the fast SMaLL-100 mobile architecture.

## Why this exists

The stock SMaLL-100 runtime is fast enough to be useful on a phone, but the generic model is not reliable enough for Turkish movie dialogue. The application therefore keeps the fast architecture and adapts the weights on subtitle-domain parallel data.

The Android runtime uses beam search with KV cache. The upstream SMaLL-100 model card evaluates generation with beam size 5; the phone uses a smaller beam of 3 to balance quality and latency.

## Data

The default corpus builder targets OPUS OpenSubtitles v2024 Turkish↔Arabic. OpenSubtitles asks users of the corpus to link/credit OpenSubtitles in reports or products that use the data. Do not commit downloaded corpus files or trained model weights to this Git repository.

Prepare up to two million cleaned aligned subtitle pairs:

```bash
python training/prepare_opensubtitles_tr_ar.py \
  --output-dir training/data \
  --max-pairs 2000000
```

Filtering removes markup, sound-effect-only rows, obvious language mismatches, URLs, pathological length ratios, and duplicates. Splits are deterministic so repeated runs keep the same held-out evaluation set.

## Fine-tune

A CUDA GPU is strongly recommended. Full movie-domain training is intentionally not put in GitHub Actions because hosted GitHub runners do not provide an appropriate GPU for this 300M-parameter seq2seq training job.

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r training/requirements.txt

python training/train_small100_movie_tr_ar.py \
  --data-dir training/data \
  --output-dir training/output/manzl-movie-tr-ar-v1 \
  --epochs 3 \
  --learning-rate 2e-5
```

The trainer evaluates with BLEU and chrF2 and saves the best checkpoint by chrF2. Generation uses beam size 5 during model evaluation so the trained checkpoint is judged using the intended high-quality decoding regime rather than greedy decoding.

## Benchmark

```bash
python training/evaluate_movie_model.py \
  training/output/manzl-movie-tr-ar-v1 \
  --test training/data/test.jsonl \
  --beams 5 \
  --limit 5000
```

Do not replace the Android default weights because training loss improved. Promote a model only after it improves held-out subtitle references and the fixed physical-device movie clip.

Acceptance gate for the project:

- two-minute Turkish clip trends toward 30 seconds end-to-end on the target Android phone;
- at least 95% dialogue coverage is the long-term target;
- Arabic meaning must be reviewed against a human subtitle reference, not merely checked for Arabic characters;
- no missing dialogue and no crashes;
- model remains offline after first download.

## Export to Android

```bash
python training/export_movie_model_onnx_int8.py \
  training/output/manzl-movie-tr-ar-v1 \
  --output-dir training/export/manzl-movie-tr-ar-v1
```

The export reproduces the Android artifact layout:

```text
encoder_model.onnx
decoder_model_merged.onnx
tokenizer.json
lang_tokens.json
```

The ONNX graphs are dynamically quantized to int8. Before publishing them to the app model host, run the same two-minute device benchmark and compare speed, dialogue coverage, and human-reviewed meaning against the previous build.
