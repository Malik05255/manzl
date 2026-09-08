# Render services

These services accept only temporary audio derivatives produced by MovieTranslator. They must never receive the original movie/video file.

Both services require the same private `AUDIO_EXTRACTOR_TOKEN`. The token is supplied only through deployment environment variables and must not be committed.
