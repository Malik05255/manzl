import concurrent.futures
import ipaddress
import os
import socket
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlparse

import requests
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

app = FastAPI(title="H AI Audio Extractor", version="1.0.1")

GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
DEFAULT_PRIMARY = "whisper-large-v3"
DEFAULT_FALLBACK = "whisper-large-v3-turbo"
BITRATE_BPS = 24_000
SEGMENT_SECONDS = 3_300
MAX_REDIRECTS = 5
MAX_SOURCE_BYTES = int(os.getenv("MAX_SOURCE_BYTES", str(8 * 1024 * 1024 * 1024)))
REQUEST_TIMEOUT = int(os.getenv("SOURCE_TIMEOUT_SECONDS", "1800"))
GROQ_TIMEOUT = int(os.getenv("GROQ_TIMEOUT_SECONDS", "600"))
LANGUAGE_NAMES = {
    "english": "en",
    "turkish": "tr",
    "japanese": "ja",
    "hindi": "hi",
    "arabic": "ar",
    "korean": "ko",
    "chinese": "zh",
    "french": "fr",
    "spanish": "es",
    "german": "de",
    "italian": "it",
    "portuguese": "pt",
    "russian": "ru",
}


class TranscribeRequest(BaseModel):
    source_url: str
    title: str = "Movie"
    language: str = "auto"
    primary_model: str = DEFAULT_PRIMARY
    fallback_model: str = DEFAULT_FALLBACK
    max_chunk_bytes: int = 24 * 1024 * 1024


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "ok": True,
        "service": "h-ai-audio-extractor",
        "ffmpeg": ffmpeg_version(),
        "chunk_seconds": SEGMENT_SECONDS,
        "bitrate_bps": BITRATE_BPS,
    }


@app.post("/transcribe")
def transcribe(req: TranscribeRequest, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(authorization)
    groq_key = os.getenv("GROQ_API_KEY", "").strip()
    if not groq_key:
        raise HTTPException(status_code=503, detail="GROQ_API_KEY is not configured")

    validate_public_url(req.source_url)
    started = time.time()

    with tempfile.TemporaryDirectory(prefix="hai-audio-") as tmp:
        tmpdir = Path(tmp)
        output_pattern = tmpdir / "chunk_%03d.ogg"
        source_meta = extract_audio_chunks(req.source_url, output_pattern)
        chunks = sorted(tmpdir.glob("chunk_*.ogg"))
        if not chunks:
            raise HTTPException(status_code=422, detail="FFmpeg did not produce audio chunks")

        oversized = [p.name for p in chunks if p.stat().st_size > req.max_chunk_bytes]
        if oversized:
            raise HTTPException(
                status_code=422,
                detail=f"Audio chunk exceeded provider limit: {', '.join(oversized[:3])}",
            )

        durations = [probe_duration_seconds(p) for p in chunks]
        detected_language = normalize_language(req.language)

        first = transcribe_chunk(
            chunks[0],
            groq_key,
            req.primary_model,
            req.fallback_model,
            None if detected_language == "auto" else detected_language,
        )
        if detected_language == "auto":
            detected_language = normalize_language(first.get("language"))

        results: list[dict[str, Any] | None] = [first] + [None] * (len(chunks) - 1)
        if len(chunks) > 1:
            with concurrent.futures.ThreadPoolExecutor(max_workers=min(3, len(chunks) - 1)) as pool:
                futures = {
                    pool.submit(
                        transcribe_chunk,
                        chunk,
                        groq_key,
                        req.primary_model,
                        req.fallback_model,
                        None if detected_language == "auto" else detected_language,
                    ): index
                    for index, chunk in enumerate(chunks[1:], start=1)
                }
                for future in concurrent.futures.as_completed(futures):
                    results[futures[future]] = future.result()

        segments: list[dict[str, Any]] = []
        offset = 0.0
        providers: list[str] = []
        for index, root in enumerate(results):
            if not root:
                continue
            provider = str(root.pop("_provider", ""))
            if provider and provider not in providers:
                providers.append(provider)
            for seg in root.get("segments") or []:
                text = " ".join(str(seg.get("text", "")).split()).strip()
                if not text:
                    continue
                local_start = max(0.0, float(seg.get("start", 0.0)))
                local_end = max(local_start + 0.001, float(seg.get("end", local_start + 0.001)))
                start = local_start + offset
                end = local_end + offset
                segments.append({"start": round(start, 3), "end": round(end, 3), "text": text})
            offset += max(0.0, durations[index])

        if not segments:
            raise HTTPException(status_code=422, detail="No intelligible dialogue was detected")

        return {
            "language": detected_language,
            "segments": segments,
            "route": "ffmpeg-opus-24k+groq-chunks",
            "providers": providers,
            "chunk_count": len(chunks),
            "audio_seconds": round(sum(durations), 3),
            "processing_ms": int((time.time() - started) * 1000),
            "source_content_type": source_meta.get("content_type"),
            "source_bytes_streamed": source_meta.get("bytes_streamed"),
        }


def extract_audio_chunks(source_url: str, output_pattern: Path) -> dict[str, Any]:
    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-i",
        "pipe:0",
        "-map",
        "0:a:0",
        "-vn",
        "-sn",
        "-dn",
        "-ac",
        "1",
        "-ar",
        "16000",
        "-c:a",
        "libopus",
        "-b:a",
        "24k",
        "-vbr",
        "on",
        "-application",
        "voip",
        "-f",
        "segment",
        "-segment_time",
        str(SEGMENT_SECONDS),
        "-reset_timestamps",
        "1",
        str(output_pattern),
    ]

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    response = None
    streamed = 0
    try:
        response = open_public_stream(source_url)
        content_length = safe_int(response.headers.get("content-length"))
        if content_length and content_length > MAX_SOURCE_BYTES:
            raise HTTPException(status_code=413, detail="Remote movie exceeds extractor safety limit")

        assert proc.stdin is not None
        for chunk in response.iter_content(chunk_size=1024 * 1024):
            if not chunk:
                continue
            streamed += len(chunk)
            if streamed > MAX_SOURCE_BYTES:
                raise HTTPException(status_code=413, detail="Remote movie exceeds extractor safety limit")
            proc.stdin.write(chunk)
        proc.stdin.close()
        stderr = proc.stderr.read().decode("utf-8", "replace") if proc.stderr else ""
        code = proc.wait(timeout=REQUEST_TIMEOUT)
        if code != 0:
            raise HTTPException(status_code=422, detail=f"FFmpeg failed: {stderr[-800:]}")
        return {
            "bytes_streamed": streamed,
            "content_type": response.headers.get("content-type", "application/octet-stream"),
        }
    except BrokenPipeError:
        stderr = proc.stderr.read().decode("utf-8", "replace") if proc.stderr else ""
        raise HTTPException(status_code=422, detail=f"FFmpeg stopped while reading source: {stderr[-800:]}")
    finally:
        if response is not None:
            response.close()
        if proc.poll() is None:
            proc.kill()


def transcribe_chunk(
    path: Path,
    api_key: str,
    primary_model: str,
    fallback_model: str,
    language: str | None,
) -> dict[str, Any]:
    last_error = ""
    tried: list[str] = []
    for model in unique_models(primary_model, fallback_model):
        tried.append(model)
        with path.open("rb") as audio:
            files = {"file": (path.name, audio, "audio/ogg")}
            data = {
                "model": model,
                "response_format": "verbose_json",
                "temperature": "0",
                "timestamp_granularities[]": "segment",
            }
            if language and language != "auto":
                data["language"] = language
            response = requests.post(
                GROQ_URL,
                headers={"Authorization": f"Bearer {api_key}"},
                files=files,
                data=data,
                timeout=GROQ_TIMEOUT,
            )
        if response.ok:
            root = response.json()
            root["_provider"] = f"groq:{model}"
            return root
        last_error = f"{model} HTTP {response.status_code}: {response.text[:300]}"
        if response.status_code not in (408, 409, 413, 429, 500, 502, 503, 504):
            break
    raise HTTPException(status_code=502, detail=f"Groq transcription failed after {tried}: {last_error}")


def open_public_stream(source_url: str) -> requests.Response:
    current = source_url
    session = requests.Session()
    session.headers.update({"User-Agent": "H-AI-Media-Extractor/1.0"})
    for _ in range(MAX_REDIRECTS + 1):
        validate_public_url(current)
        response = session.get(current, stream=True, timeout=(20, REQUEST_TIMEOUT), allow_redirects=False)
        if response.status_code in (301, 302, 303, 307, 308):
            location = response.headers.get("location")
            response.close()
            if not location:
                raise HTTPException(status_code=502, detail="Remote source returned an empty redirect")
            current = urljoin(current, location)
            continue
        if not response.ok:
            body = response.text[:300]
            response.close()
            raise HTTPException(status_code=502, detail=f"Remote source HTTP {response.status_code}: {body}")
        return response
    raise HTTPException(status_code=502, detail="Remote source redirected too many times")


def validate_public_url(value: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise HTTPException(status_code=400, detail="source_url must be a public HTTP/HTTPS URL")
    host = parsed.hostname.strip().lower()
    if host in {"localhost", "0.0.0.0", "::1"} or host.endswith(".local"):
        raise HTTPException(status_code=400, detail="Private/local URLs are not allowed")
    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80))}
    except socket.gaierror as exc:
        raise HTTPException(status_code=400, detail=f"Unable to resolve source host: {exc}") from exc
    if not addresses:
        raise HTTPException(status_code=400, detail="Unable to resolve source host")
    for raw in addresses:
        ip = ipaddress.ip_address(raw)
        if not ip.is_global:
            raise HTTPException(status_code=400, detail="Private/reserved source addresses are not allowed")


def probe_duration_seconds(path: Path) -> float:
    proc = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ],
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    if proc.returncode != 0:
        return float(SEGMENT_SECONDS)
    try:
        return max(0.0, float(proc.stdout.strip()))
    except ValueError:
        return float(SEGMENT_SECONDS)


def require_token(authorization: str | None) -> None:
    expected = os.getenv("AUDIO_EXTRACTOR_TOKEN", "").strip()
    if not expected:
        return
    supplied = ""
    if authorization and authorization.lower().startswith("bearer "):
        supplied = authorization[7:].strip()
    if supplied != expected:
        raise HTTPException(status_code=401, detail="unauthorized")


def normalize_language(value: Any) -> str:
    raw = str(value or "auto").strip().lower().replace("_", "-")
    if not raw or raw == "auto":
        return "auto"
    if raw in LANGUAGE_NAMES:
        return LANGUAGE_NAMES[raw]
    code = raw.split("-", 1)[0]
    return code if 2 <= len(code) <= 3 and code.isalpha() else "auto"


def unique_models(primary: str, fallback: str) -> list[str]:
    valid = {DEFAULT_PRIMARY, DEFAULT_FALLBACK}
    out: list[str] = []
    for model in (primary, fallback):
        if model in valid and model not in out:
            out.append(model)
    return out or [DEFAULT_PRIMARY, DEFAULT_FALLBACK]


def ffmpeg_version() -> str:
    try:
        proc = subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True, timeout=5, check=False)
        return proc.stdout.splitlines()[0] if proc.stdout else "unknown"
    except Exception:
        return "unavailable"


def safe_int(value: str | None) -> int | None:
    try:
        parsed = int(value or "")
        return parsed if parsed >= 0 else None
    except ValueError:
        return None
