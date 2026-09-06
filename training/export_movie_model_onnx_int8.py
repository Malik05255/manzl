#!/usr/bin/env python3
"""Export a fine-tuned Manzl SMaLL-100 checkpoint to the Android two-file int8 runtime."""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model_dir", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("training/export/manzl-movie-tr-ar-v1"))
    args = parser.parse_args()

    model_dir = args.model_dir.resolve()
    output_dir = args.output_dir.resolve()
    fp32_dir = output_dir.parent / f"{output_dir.name}-fp32"
    if not model_dir.is_dir():
        raise SystemExit(f"Model directory does not exist: {model_dir}")

    if fp32_dir.exists():
        shutil.rmtree(fp32_dir)
    fp32_dir.mkdir(parents=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    subprocess.run(
        [
            sys.executable,
            "-m",
            "optimum.exporters.onnx",
            "--model",
            str(model_dir),
            "--task",
            "text2text-generation-with-past",
            str(fp32_dir),
        ],
        check=True,
    )

    encoder = fp32_dir / "encoder_model.onnx"
    merged = fp32_dir / "decoder_model_merged.onnx"
    if not merged.is_file():
        raise SystemExit(
            "Optimum did not emit decoder_model_merged.onnx. Use an Optimum version that supports merged seq2seq decoders."
        )

    quantize_dynamic(str(encoder), str(output_dir / "encoder_model.onnx"), weight_type=QuantType.QInt8)
    quantize_dynamic(
        str(merged),
        str(output_dir / "decoder_model_merged.onnx"),
        weight_type=QuantType.QInt8,
        extra_options={"EnableSubgraph": True},
    )

    for name in (
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "added_tokens.json",
        "vocab.json",
        "sentencepiece.bpe.model",
        "config.json",
        "generation_config.json",
    ):
        src = model_dir / name
        if src.is_file():
            shutil.copy2(src, output_dir / name)

    # Android only needs Arabic, but keeping a tiny explicit manifest prevents token-order mistakes.
    tokenizer_cfg = json.loads((output_dir / "tokenizer_config.json").read_text(encoding="utf-8"))
    specials = tokenizer_cfg.get("additional_special_tokens", [])
    try:
        ar_index = specials.index("__ar__")
    except ValueError as exc:
        raise SystemExit("Fine-tuned tokenizer does not expose __ar__") from exc
    vocab_size = 128004
    lang_to_id = {"ar": vocab_size + ar_index}
    manifest = {
        "model": "manzl-movie-tr-ar-v1",
        "base": "alirezamsh/small100",
        "lang_to_id": lang_to_id,
        "eos": 2,
        "pad": 1,
        "unk": 3,
        "decoder_start": 2,
    }
    (output_dir / "lang_tokens.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(f"Android model exported to {output_dir}")
    print(f"encoder={(output_dir / 'encoder_model.onnx').stat().st_size / 1e6:.1f} MB")
    print(f"decoder={(output_dir / 'decoder_model_merged.onnx').stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
