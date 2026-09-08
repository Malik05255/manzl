import os
import subprocess
import tempfile
from pathlib import Path

import imageio_ffmpeg
from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

app = FastAPI(title="MovieTranslator Audio Normalizer", version="1.0.0")
MAX_INPUT_BYTES = 24 * 1024 * 1024


def expected_token() -> str:
    return os.getenv("AUDIO_EXTRACTOR_TOKEN", "").strip()


def require_token(value: str | None) -> None:
    expected = expected_token()
    if not expected:
        raise HTTPException(status_code=503, detail="service_not_configured")
    if not value or value != expected:
        raise HTTPException(status_code=401, detail="invalid_token")


def cleanup(*paths: str) -> None:
    for value in paths:
        try:
            Path(value).unlink(missing_ok=True)
        except Exception:
            pass


@app.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "service": "audio-normalizer",
        "token_configured": bool(expected_token()),
        "ffmpeg": bool(imageio_ffmpeg.get_ffmpeg_exe()),
    }


@app.post("/normalize")
async def normalize(
    audio: UploadFile = File(...),
    x_audio_extractor_token: str | None = Header(default=None),
):
    require_token(x_audio_extractor_token)
    content_type = (audio.content_type or "").lower()
    if content_type.startswith("video/"):
        raise HTTPException(status_code=415, detail="video_upload_forbidden")

    suffix = Path(audio.filename or "audio.bin").suffix[:12] or ".bin"
    source_fd, source_path = tempfile.mkstemp(prefix="manzl-src-", suffix=suffix)
    os.close(source_fd)
    output_fd, output_path = tempfile.mkstemp(prefix="manzl-norm-", suffix=".ogg")
    os.close(output_fd)

    total = 0
    try:
        with open(source_path, "wb") as target:
            while True:
                chunk = await audio.read(256 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_INPUT_BYTES:
                    raise HTTPException(status_code=413, detail="audio_too_large")
                target.write(chunk)

        ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
        completed = subprocess.run(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                source_path,
                "-vn",
                "-sn",
                "-dn",
                "-map",
                "0:a:0",
                "-ac",
                "1",
                "-ar",
                "16000",
                "-c:a",
                "libopus",
                "-b:a",
                "24k",
                "-f",
                "ogg",
                output_path,
            ],
            capture_output=True,
            text=True,
            timeout=180,
        )
        if completed.returncode != 0 or not Path(output_path).is_file() or Path(output_path).stat().st_size <= 0:
            detail = (completed.stderr or "audio_normalization_failed")[-600:]
            raise HTTPException(status_code=422, detail=detail)

        return FileResponse(
            output_path,
            media_type="audio/ogg",
            filename="normalized.ogg",
            headers={"x-movie-audio-normalized": "1"},
            background=BackgroundTask(cleanup, source_path, output_path),
        )
    except HTTPException:
        cleanup(source_path, output_path)
        raise
    except subprocess.TimeoutExpired as exc:
        cleanup(source_path, output_path)
        raise HTTPException(status_code=504, detail="normalization_timeout") from exc
    except Exception as exc:
        cleanup(source_path, output_path)
        raise HTTPException(status_code=500, detail="normalization_failed") from exc
