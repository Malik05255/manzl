-- Private runtime configuration for MovieTranslator's optional Render/Cloudflare path.
-- Provider keys are never stored here by migrations. Values are inserted server-side after deploy.
create table if not exists public.movie_translator_runtime_config (
    key text primary key,
    value text not null,
    enabled boolean not null default true,
    updated_at timestamptz not null default now(),
    constraint movie_translator_runtime_config_key_check check (
        key in (
            'render_normalizer_url',
            'render_asr_url',
            'cloudflare_worker_url',
            'audio_extractor_token'
        )
    )
);

alter table public.movie_translator_runtime_config enable row level security;
revoke all on table public.movie_translator_runtime_config from anon, authenticated;
comment on table public.movie_translator_runtime_config is
    'Server-only routing configuration for MovieTranslator. Never expose values to the Android client.';
