const endpoint = 'https://abavsspydbpkudhswmzp.supabase.co/functions/v1/media-gateway';
const publishableKey = 'sb_publishable_iuZnOH7ye1WITm-xc44TiQ_CNb2d2qB';

async function post(payload, includeKey = true) {
  const headers = { 'content-type': 'application/json' };
  if (includeKey) {
    headers.apikey = publishableKey;
    headers.authorization = `Bearer ${publishableKey}`;
  }
  const response = await fetch(endpoint, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
  });
  const text = await response.text();
  let body = {};
  try { body = JSON.parse(text); } catch { body = { raw: text }; }
  return { status: response.status, body };
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const plan = await post({
  mode: 'plan',
  source_kind: 'url',
  duration_ms: 2 * 60 * 60 * 1000,
  language: 'auto',
  retention: '7d',
});
assert(plan.status === 200, `plan expected 200, got ${plan.status}: ${JSON.stringify(plan.body)}`);
assert(plan.body?.status === 'ok', 'plan response is not ok');
assert(plan.body?.plan?.targetLanguage === 'ar', 'target language is not Arabic');
assert(plan.body?.plan?.asrPrimary === 'whisper-large-v3', '2h auto-language job should use quality-first Whisper V3');
assert(Number(plan.body?.daily_job_limit) === 5, 'development daily free job limit should be 5');

const accountKey = 'smoke_' + 'a'.repeat(38);
const list = await post({ mode: 'list_jobs', account_key: accountKey });
assert(list.status === 200, `list_jobs expected 200, got ${list.status}: ${JSON.stringify(list.body)}`);
assert(Array.isArray(list.body?.jobs), 'list_jobs did not return an array');

const unauthenticated = await post({ mode: 'plan' }, false);
assert(unauthenticated.status === 401, `missing apikey expected 401, got ${unauthenticated.status}`);
assert(unauthenticated.body?.error === 'invalid_api_key', 'missing apikey did not hit gateway API-key guard');

console.log(JSON.stringify({
  ok: true,
  revision: plan.body?.revision,
  route: plan.body?.plan?.asrPrimary,
  daily_job_limit: plan.body?.daily_job_limit,
  library_count: list.body.jobs.length,
}));
