# MovieTranslator runtime pipeline

This pipeline extends the existing translator without changing the privacy contract: the original movie stays on the Android device. Only temporary audio derivatives may enter the cloud path.

## Runtime route

1. Android uploads prepared audio to Supabase `movie-gateway`.
2. Short audio prefers Render `groq-asr` and safely falls back to the existing `movie-translate` function.
3. Long audio (15 minutes or more) uses Cloudflare R2 + Workflow when configured.
4. The Workflow stores only the temporary audio derivative, calls Render `audio-normalizer`, then Render `groq-asr`, and deletes the R2 object after success.
5. Translation/review JSON modes continue through the existing `movie-translate` implementation.

## Render services

- `manzl-audio-normalizer`
  - Build: `pip install -r backend/render/audio-normalizer/requirements.txt`
  - Start: `cd backend/render/audio-normalizer && uvicorn app:app --host 0.0.0.0 --port $PORT`
  - Required env: `AUDIO_EXTRACTOR_TOKEN`
- `manzl-groq-asr`
  - Build: `pip install -r backend/render/groq-asr/requirements.txt`
  - Start: `cd backend/render/groq-asr && uvicorn app:app --host 0.0.0.0 --port $PORT`
  - Required env: `AUDIO_EXTRACTOR_TOKEN`
  - Preferred env: `GROQ_API_KEY`
  - Safe fallback env: `SUPABASE_ASR_FALLBACK_URL`, `SUPABASE_PUBLISHABLE_KEY`, `SUPABASE_ANON_JWT`

## Cloudflare

The Worker configuration is in `cloudflare/movie-workflow/wrangler.toml` and binds:

- Workflow: `manzl-movie-audio-workflow`
- R2: `manzl-movie-audio`

GitHub deployment requires repository secrets `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, and `MOVIE_AUDIO_EXTRACTOR_TOKEN`.

## Supabase runtime config

`movie_translator_runtime_config` is server-only. The gateway recognizes these keys:

- `render_normalizer_url`
- `render_asr_url`
- `cloudflare_worker_url`
- `audio_extractor_token`

Provider API keys must not be committed to Git.
