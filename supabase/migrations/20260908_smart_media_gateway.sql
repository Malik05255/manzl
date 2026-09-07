-- Smart media gateway: durable job state, reusable results, and provider telemetry.
-- Clients never write these tables directly; Edge Functions / orchestration use server credentials.

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

-- Server-side only rolling telemetry. This is the memory used by the Smart Router.
create or replace function public.record_provider_route_sample(
  p_provider text,
  p_task text,
  p_source_language text,
  p_duration_bucket text,
  p_ok boolean,
  p_latency_ms double precision,
  p_quality_score double precision default null,
  p_error text default null
) returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.provider_route_stats (
    provider,
    task,
    source_language,
    duration_bucket,
    samples,
    successes,
    failures,
    avg_latency_ms,
    quality_score,
    last_error,
    last_used_at,
    updated_at
  ) values (
    p_provider,
    p_task,
    coalesce(nullif(p_source_language, ''), 'auto'),
    coalesce(nullif(p_duration_bucket, ''), 'unknown'),
    1,
    case when p_ok then 1 else 0 end,
    case when p_ok then 0 else 1 end,
    greatest(coalesce(p_latency_ms, 0), 0),
    coalesce(p_quality_score, case when p_ok then 0.90 else 0.50 end),
    case when p_ok then null else p_error end,
    now(),
    now()
  )
  on conflict (provider, task, source_language, duration_bucket)
  do update set
    samples = provider_route_stats.samples + 1,
    successes = provider_route_stats.successes + case when excluded.successes > 0 then 1 else 0 end,
    failures = provider_route_stats.failures + case when excluded.failures > 0 then 1 else 0 end,
    avg_latency_ms = case
      when provider_route_stats.samples <= 0 then excluded.avg_latency_ms
      else ((provider_route_stats.avg_latency_ms * provider_route_stats.samples) + excluded.avg_latency_ms)
        / (provider_route_stats.samples + 1)
    end,
    quality_score = case
      when p_quality_score is null then provider_route_stats.quality_score
      else (provider_route_stats.quality_score * 0.80) + (greatest(0, least(1, p_quality_score)) * 0.20)
    end,
    last_error = case when p_ok then provider_route_stats.last_error else p_error end,
    last_used_at = now(),
    updated_at = now();
end;
$$;

revoke all on function public.record_provider_route_sample(text,text,text,text,boolean,double precision,double precision,text) from public;
revoke all on function public.record_provider_route_sample(text,text,text,text,boolean,double precision,double precision,text) from anon;
revoke all on function public.record_provider_route_sample(text,text,text,text,boolean,double precision,double precision,text) from authenticated;
grant execute on function public.record_provider_route_sample(text,text,text,text,boolean,double precision,double precision,text) to service_role;

alter table public.media_jobs enable row level security;
alter table public.provider_route_stats enable row level security;

-- Intentionally no public policies. All access goes through the gateway.
comment on table public.media_jobs is 'Private Smart Media jobs managed only by server-side gateway credentials.';
comment on table public.provider_route_stats is 'Adaptive provider telemetry used by the Smart Router; no client writes.';
comment on function public.record_provider_route_sample is 'Accumulates provider reliability/latency/quality for adaptive routing.';
