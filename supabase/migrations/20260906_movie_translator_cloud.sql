create table if not exists public.movie_translator_devices (
  device_hash text primary key check (length(device_hash) = 64),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);
alter table public.movie_translator_devices enable row level security;

create table if not exists public.movie_translator_state (
  singleton boolean primary key default true check (singleton),
  owner_hash text not null check (length(owner_hash) = 64),
  opaque_payload text not null,
  updated_at timestamptz not null default now()
);
alter table public.movie_translator_state enable row level security;

create table if not exists public.movie_translator_library (
  id uuid primary key default gen_random_uuid(),
  device_hash text not null check (length(device_hash) = 64),
  movie_key text not null,
  movie_name text not null,
  video_uri text,
  duration_ms bigint not null default 0,
  srt_text text,
  cue_count integer not null default 0,
  processing_ms bigint not null default 0,
  updated_at timestamptz not null default now()
);
alter table public.movie_translator_library enable row level security;
create unique index if not exists movie_translator_library_device_movie_key_uq
  on public.movie_translator_library (device_hash, movie_key);

create table if not exists public.movie_translator_usage (
  device_hash text primary key check (length(device_hash) = 64),
  usage_day date not null default current_date,
  groq_audio_seconds bigint not null default 0,
  gemini_audio_seconds bigint not null default 0,
  gemini_requests integer not null default 0,
  updated_at timestamptz not null default now()
);
alter table public.movie_translator_usage enable row level security;

-- No anon table policies by design. The Android app talks only to verified Edge Functions;
-- those functions use the service-role client after checking the app-scoped device hash.
