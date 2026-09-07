-- Smart media gateway: durable job state, reusable results, and provider telemetry.
-- Clients never write these tables directly; Edge Functions use server-side credentials.

create table if not exists public.media_jobs (
  id uuid primary key default gen_random_uuid(),
  client_id text not null,
  title text not null default 'Movie',
  source_kind text not null check (source_kind in ('url','local')),
  source_url text,
  playback_url text,
  storage_provider text,
  retention text not null default 'none' check (retention in ('none','1d','7d','30d','permanent')),
  expires_at timestamptz,
  source_language text,
  target_language text not null default 'ar',
  status text not null default 'queued' check (status in ('queued','processing','completed','failed','cancelled')),
  progress real not null default 0 check (progress >= 0 and progress <= 1),
  stage text not null default 'queued',
  provider_trace jsonb not null default '[]'::jsonb,
  transcript jsonb not null default '[]'::jsonb,
  subtitles jsonb not null default '[]'::jsonb,
  srt_text text,
  vtt_text text,
  summary jsonb,
  error text,
  started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists media_jobs_client_created_idx
  on public.media_jobs (client_id, created_at desc);
create index if not exists media_jobs_status_idx
  on public.media_jobs (status, updated_at desc);
create index if not exists media_jobs_expires_idx
  on public.media_jobs (expires_at)
  where expires_at is not null;

create table if not exists public.provider_route_stats (
  provider text not null,
  task text not null,
  source_language text not null default 'auto',
  duration_bucket text not null default 'unknown',
  samples bigint not null default 0,
  successes bigint not null default 0,
  failures bigint not null default 0,
  avg_latency_ms double precision not null default 0,
  quality_score double precision not null default 0.80,
  last_error text,
  last_used_at timestamptz,
  updated_at timestamptz not null default now(),
  primary key (provider, task, source_language, duration_bucket)
);

alter table public.media_jobs enable row level security;
alter table public.provider_route_stats enable row level security;

-- Intentionally no public policies. All access goes through the gateway.
comment on table public.media_jobs is 'Private Smart Media jobs managed only by server-side gateway credentials.';
comment on table public.provider_route_stats is 'Adaptive provider telemetry used by the Smart Router; no client writes.';
