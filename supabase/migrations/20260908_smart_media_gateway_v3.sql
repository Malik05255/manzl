-- Smart Media v3: expose routing diagnostics and a direct subtitle track URL.
alter table public.media_jobs
  add column if not exists source_size_bytes bigint,
  add column if not exists source_mime text,
  add column if not exists asr_route text,
  add column if not exists subtitle_url text;

create index if not exists media_jobs_asr_route_idx
  on public.media_jobs (asr_route)
  where asr_route is not null;

comment on column public.media_jobs.source_size_bytes is 'Remote source size discovered during server-side preflight.';
comment on column public.media_jobs.source_mime is 'Remote source MIME type discovered during server-side preflight.';
comment on column public.media_jobs.asr_route is 'Actual speech-recognition transport/provider route used for this movie.';
comment on column public.media_jobs.subtitle_url is 'Direct WebVTT URL for the web/Android player when available.';
