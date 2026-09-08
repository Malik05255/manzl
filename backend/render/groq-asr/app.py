import os
from typing import Any

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse

app = FastAPI(title="MovieTranslator Groq ASR", version="1.0.0")
MAX_INPUT_BYTES = 24 * 1024 * 1024
GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"


def env(name: str) -> str:
    return os.getenv(name, "").strip()


def require_token(value: str | None) -> None:
    expected = env("AUDIO_EXTRACTOR_TOKEN")
    if not expected:
        raise HTTPException(status_code=503, detail="service_not_configured")
    if not value or value != expected:
        raise HTTPException(status_code=401, detail="invalid_token")


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "ok": True,
        "service": "groq-asr",
        "token_configured": bool(env("AUDIO_EXTRACTOR_TOKEN")),
        "groq_configured": bool(env("GROQ_API_KEY")),
        "supabase_fallback_configured": bool(env("SUPABASE_ASR_FALLBACK_URL")),
    }


@app.post("/asr")
async def asr(
    audio: UploadFile = File(...),
    mode: str = Form(default="asr"),
    provider: str = Form(default="groq"),
    offset_ms: int = Form(default=0),
    duration_ms: int = Form(default=0),
    device_hash: str = Form(default=""),
    x_audio_extractor_token: str | None = Header(default=None),
):
    require_token(x_audio_extractor_token)
    if mode != "asr":
        raise HTTPException(status_code=400, detail="invalid_mode")

    payload = await audio.read(MAX_INPUT_BYTES + 1)
    if not payload:
        raise HTTPException(status_code=400, detail="audio_required")
    if len(payload) > MAX_INPUT_BYTES:
        raise HTTPException(status_code=413, detail="audio_too_large")

    filename = audio.filename or "audio.ogg"
    content_type = audio.content_type or "application/octet-stream"
    groq_key = env("GROQ_API_KEY")

    if groq_key:
        try:
            result = await transcribe_groq(
                payload=payload,
                filename=filename,
                content_type=content_type,
                provider=provider,
                offset_ms=max(0, offset_ms),
                api_key=groq_key,
            )
            return JSONResponse(result, headers={"x-movie-route": "render-groq"})
        except Exception as exc:
            # The legacy Supabase function remains a zero-downtime fallback while Render is tested.
            fallback = await transcribe_supabase_fallback(
                payload=payload,
                filename=filename,
                content_type=content_type,
                provider=provider,
                offset_ms=max(0, offset_ms),
                duration_ms=max(0, duration_ms),
                device_hash=device_hash,
            )
            if fallback is not None:
                return fallback
            raise HTTPException(status_code=503, detail="groq_temporarily_unavailable") from exc

    fallback = await transcribe_supabase_fallback(
        payload=payload,
        filename=filename,
        content_type=content_type,
        provider=provider,
        offset_ms=max(0, offset_ms),
        duration_ms=max(0, duration_ms),
        device_hash=device_hash,
    )
    if fallback is not None:
        return fallback
    raise HTTPException(status_code=503, detail="groq_key_missing")


async def transcribe_groq(
    *,
    payload: bytes,
    filename: str,
    content_type: str,
    provider: str,
    offset_ms: int,
    api_key: str,
) -> dict[str, Any]:
    model = "whisper-large-v3-turbo" if provider.lower() == "groq_turbo" else "whisper-large-v3"
    timeout = httpx.Timeout(connect=15.0, read=120.0, write=120.0, pool=15.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            GROQ_URL,
            headers={"Authorization": f"Bearer {api_key}"},
            files={"file": (filename, payload, content_type)},
            data={
                "model": model,
                "language": "tr",
                "response_format": "verbose_json",
                "temperature": "0",
            },
        )
    if response.status_code >= 400:
        raise RuntimeError(f"groq_{response.status_code}")
    root = response.json()
    segments: list[dict[str, Any]] = []
    for index, item in enumerate(root.get("segments") or []):
        text = str(item.get("text") or "").strip()
        if not text:
            continue
        start = float(item.get("start") or 0.0)
        end = float(item.get("end") or start)
        segments.append(
            {
                "id": index,
                "start_ms": offset_ms + max(0, round(start * 1000)),
                "end_ms": offset_ms + max(1, round(end * 1000)),
                "tr": text,
            }
        )
    if not segments:
        raise RuntimeError("groq_empty_segments")
    return {
        "status": "completed",
        "segments": segments,
        "metrics": {"provider": model},
    }


async def transcribe_supabase_fallback(
    *,
    payload: bytes,
    filename: str,
    content_type: str,
    provider: str,
    offset_ms: int,
    duration_ms: int,
    device_hash: str,
) -> JSONResponse | None:
    url = env("SUPABASE_ASR_FALLBACK_URL")
    publishable = env("SUPABASE_PUBLISHABLE_KEY")
    anon_jwt = env("SUPABASE_ANON_JWT")
    if not url or not publishable or not anon_jwt:
        return None

    timeout = httpx.Timeout(connect=15.0, read=135.0, write=135.0, pool=15.0)
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(
                url,
                headers={
                    "apikey": publishable,
                    "Authorization": f"Bearer {anon_jwt}",
                    "Accept": "application/json",
                },
                files={"audio": (filename, payload, content_type)},
                data={
                    "mode": "asr",
                    "provider": provider,
                    "offset_ms": str(offset_ms),
                    "duration_ms": str(duration_ms),
                    "device_hash": device_hash,
                },
            )
        body = response.json() if response.content else {}
        return JSONResponse(
            body,
            status_code=response.status_code,
            headers={"x-movie-route": "render-supabase-fallback"},
        )
    except Exception:
        return None
