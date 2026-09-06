#!/usr/bin/env python3
"""Evaluate a Turkish->Arabic movie model against held-out human subtitle references."""
from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path

import torch
from sacrebleu.metrics import BLEU, CHRF
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer


def load_rows(path: Path, limit: int | None) -> list[dict[str, str]]:
    rows = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                rows.append(json.loads(line))
                if limit and len(rows) >= limit:
                    break
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("--test", type=Path, default=Path("training/data/test.jsonl"))
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--beams", type=int, default=5)
    parser.add_argument("--output", type=Path, default=Path("training/output/benchmark.json"))
    args = parser.parse_args()

    rows = load_rows(args.test, args.limit)
    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    if hasattr(tokenizer, "tgt_lang"):
        tokenizer.tgt_lang = "ar"
    model = AutoModelForSeq2SeqLM.from_pretrained(args.model)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device).eval()

    predictions: list[str] = []
    latencies: list[float] = []
    with torch.inference_mode():
        for start in range(0, len(rows), args.batch_size):
            batch = rows[start : start + args.batch_size]
            encoded = tokenizer(
                [row["src"] for row in batch],
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=192,
            ).to(device)
            started = time.perf_counter()
            generated = model.generate(
                **encoded,
                num_beams=args.beams,
                max_new_tokens=192,
                early_stopping=True,
            )
            elapsed = time.perf_counter() - started
            latencies.extend([elapsed / len(batch)] * len(batch))
            predictions.extend(tokenizer.batch_decode(generated, skip_special_tokens=True))

    refs = [row["tgt"] for row in rows]
    bleu = BLEU(tokenize="intl").corpus_score(predictions, [refs]).score
    chrf = CHRF(word_order=2).corpus_score(predictions, [refs]).score
    report = {
        "model": args.model,
        "samples": len(rows),
        "beam_size": args.beams,
        "bleu": bleu,
        "chrf2": chrf,
        "latency_median_ms": statistics.median(latencies) * 1000 if latencies else None,
        "latency_p95_ms": sorted(latencies)[max(0, int(len(latencies) * 0.95) - 1)] * 1000 if latencies else None,
        "examples": [
            {"src": row["src"], "ref": row["tgt"], "pred": pred}
            for row, pred in list(zip(rows, predictions))[:50]
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "examples"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
