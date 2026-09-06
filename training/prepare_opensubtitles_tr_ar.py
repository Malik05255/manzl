#!/usr/bin/env python3
"""Build a clean Turkish -> Arabic movie-dialogue corpus from OPUS OpenSubtitles.

The script accepts either an existing OPUS Moses zip or downloads the v2024 tr-ar pair.
It aggressively removes subtitle noise, malformed alignments, duplicates, and obvious
language mismatches, then writes deterministic train/dev/test JSONL files.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import shutil
import urllib.request
import zipfile
from pathlib import Path
from typing import Iterable

DEFAULT_URL = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2024/moses/tr-ar.txt.zip"
TAG_RE = re.compile(r"<[^>]+>|\{\\[^}]+\}")
SPACE_RE = re.compile(r"\s+")
URL_RE = re.compile(r"(?:https?://|www\.)", re.I)
NOISE_RE = re.compile(r"^\s*[\[(].{0,70}[\])][.!?…\s-]*$")
ARABIC_RE = re.compile(r"[\u0600-\u06ff]")
LATIN_RE = re.compile(r"[A-Za-zÇĞİÖŞÜçğıöşü]")


def normalize(text: str) -> str:
    text = html.unescape(text).replace("\ufeff", " ")
    text = TAG_RE.sub(" ", text)
    text = text.replace("♪", " ").replace("♫", " ")
    text = text.replace("–", "-").replace("—", "-")
    return SPACE_RE.sub(" ", text).strip(" \t-–—\"'“”‘’")


def script_ratio(text: str, regex: re.Pattern[str]) -> float:
    letters = sum(ch.isalpha() for ch in text)
    if letters == 0:
        return 0.0
    return len(regex.findall(text)) / letters


def valid_pair(src: str, tgt: str) -> bool:
    if not src or not tgt or src == tgt:
        return False
    if URL_RE.search(src) or URL_RE.search(tgt):
        return False
    if NOISE_RE.match(src) or NOISE_RE.match(tgt):
        return False
    if not (2 <= len(src) <= 240 and 2 <= len(tgt) <= 260):
        return False
    shorter = max(1, min(len(src), len(tgt)))
    longer = max(len(src), len(tgt))
    if longer / shorter > 4.0:
        return False
    if script_ratio(src, LATIN_RE) < 0.60:
        return False
    if script_ratio(tgt, ARABIC_RE) < 0.60:
        return False
    return True


def stable_split(src: str, tgt: str) -> str:
    digest = hashlib.blake2b(f"{src}\t{tgt}".encode("utf-8"), digest_size=8).digest()
    bucket = int.from_bytes(digest, "big") % 1000
    if bucket < 15:
        return "test"
    if bucket < 30:
        return "dev"
    return "train"


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size > 1_000_000:
        return
    request = urllib.request.Request(url, headers={"User-Agent": "Manzl-MovieTranslator/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response, target.open("wb") as out:
        shutil.copyfileobj(response, out, length=1024 * 1024)


def find_parallel_files(extracted: Path) -> tuple[Path, Path]:
    files = [p for p in extracted.rglob("*") if p.is_file()]
    tr = next((p for p in files if p.name.endswith(".tr")), None)
    ar = next((p for p in files if p.name.endswith(".ar")), None)
    if not tr or not ar:
        raise RuntimeError("Could not find aligned .tr and .ar files inside the OPUS archive")
    return tr, ar


def aligned_lines(src_path: Path, tgt_path: Path) -> Iterable[tuple[str, str]]:
    with src_path.open(encoding="utf-8", errors="replace") as src_file, tgt_path.open(
        encoding="utf-8", errors="replace"
    ) as tgt_file:
        for src_raw, tgt_raw in zip(src_file, tgt_file):
            yield normalize(src_raw), normalize(tgt_raw)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-dir", type=Path, default=Path("training/work"))
    parser.add_argument("--output-dir", type=Path, default=Path("training/data"))
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--max-pairs", type=int, default=2_000_000)
    args = parser.parse_args()

    args.work_dir.mkdir(parents=True, exist_ok=True)
    archive = args.archive or args.work_dir / "OpenSubtitles-v2024-tr-ar.zip"
    if not archive.exists():
        print(f"Downloading {args.url}")
        download(args.url, archive)

    extracted = args.work_dir / "opensubtitles-tr-ar"
    if not extracted.exists():
        extracted.mkdir(parents=True)
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(extracted)

    src_path, tgt_path = find_parallel_files(extracted)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    handles = {
        split: (args.output_dir / f"{split}.jsonl").open("w", encoding="utf-8")
        for split in ("train", "dev", "test")
    }
    counts = {"seen": 0, "kept": 0, "train": 0, "dev": 0, "test": 0}
    dedupe: set[bytes] = set()

    try:
        for src, tgt in aligned_lines(src_path, tgt_path):
            counts["seen"] += 1
            if not valid_pair(src, tgt):
                continue
            key = hashlib.blake2b(f"{src.lower()}\t{tgt}".encode("utf-8"), digest_size=16).digest()
            if key in dedupe:
                continue
            dedupe.add(key)
            split = stable_split(src, tgt)
            json.dump({"src": src, "tgt": tgt, "domain": "opensubtitles"}, handles[split], ensure_ascii=False)
            handles[split].write("\n")
            counts[split] += 1
            counts["kept"] += 1
            if counts["kept"] >= args.max_pairs:
                break
    finally:
        for handle in handles.values():
            handle.close()

    (args.output_dir / "stats.json").write_text(
        json.dumps(counts, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(counts, ensure_ascii=False))


if __name__ == "__main__":
    main()
