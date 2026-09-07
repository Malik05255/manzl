-- Hard zero-bill guard for the personal Smart Media gateway.
-- The server reserves a slot before starting a cloud movie job.
create table if not exists public.media_daily_quota (
  day date primary key,
  jobs_started integer not null default 0 check (jobs_started >= 0),
  updated_at timestamptz not null default now()
);

alter table public.media_daily_quota enable row level security;

create or replace function public.reserve_media_daily_slot(p_limit integer default 5)
returns table(allowed boolean, used integer, quota_limit integer)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_limit integer := greatest(1, least(coalesce(p_limit, 5), 100));
  v_used integer;
begin
  insert into public.media_daily_quota(day, jobs_started, updated_at)
  values (current_date, 0, now())
  on conflict (day) do nothing;

  update public.media_daily_quota
     set jobs_started = jobs_started + 1,
         updated_at = now()
   where day = current_date
     and jobs_started < v_limit
  returning jobs_started into v_used;

  if found then
    return query select true, v_used, v_limit;
    return;
  end if;

  select q.jobs_started into v_used
    from public.media_daily_quota q
   where q.day = current_date;

  return query select false, coalesce(v_used, 0), v_limit;
end;
$$;

revoke all on table public.media_daily_quota from public, anon, authenticated;
revoke all on function public.reserve_media_daily_slot(integer) from public, anon, authenticated;
grant execute on function public.reserve_media_daily_slot(integer) to service_role;

comment on table public.media_daily_quota is 'Server-only zero-bill daily job counter for H AI Smart Media.';
comment on function public.reserve_media_daily_slot(integer) is 'Atomically reserves one Smart Media job slot without exceeding the configured daily limit.';
