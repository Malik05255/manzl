import {
  durationBucket,
  isSafeRemoteMediaUrl,
  makeRoutePlan,
  normalizeLanguage,
  normalizeRetention,
  retentionExpiry,
} from './smart-media-router.ts';

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test('long and unknown-language movies prefer quality-first Whisper V3', () => {
  const long = makeRoutePlan({ sourceKind: 'url', durationMs: 2 * 60 * 60_000, language: 'en', retention: '7d' });
  assert(long.asrPrimary === 'whisper-large-v3', 'long movie must use V3 first');
  assert(long.asrFallback === 'whisper-large-v3-turbo', 'long movie fallback must be Turbo');

  const unknown = makeRoutePlan({ sourceKind: 'url', durationMs: 10 * 60_000, language: 'auto' });
  assert(unknown.asrPrimary === 'whisper-large-v3', 'unknown language must use V3 first');
});

Deno.test('short known-language clips can use Turbo first', () => {
  const plan = makeRoutePlan({ sourceKind: 'url', durationMs: 8 * 60_000, language: 'tr' });
  assert(plan.asrPrimary === 'whisper-large-v3-turbo', 'short Turkish clip should use Turbo first');
  assert(plan.translationOrder[0] === 'groq', 'bulk translation must be free-first Groq');
  assert(plan.translationOrder[1] === 'azure', 'Azure must stay the fallback');
});

Deno.test('Japanese and Hindi stay quality-first even when short', () => {
  for (const language of ['ja', 'hi']) {
    const plan = makeRoutePlan({ sourceKind: 'url', durationMs: 5 * 60_000, language });
    assert(plan.asrPrimary === 'whisper-large-v3', `${language} should use V3 first`);
  }
});

Deno.test('retention and duration normalization are stable', () => {
  assert(normalizeRetention('7D') === '7d', 'retention should normalize case');
  assert(normalizeRetention('garbage') === 'none', 'invalid retention should become none');
  assert(durationBucket(19 * 60_000) === 'short', '19m should be short');
  assert(durationBucket(60 * 60_000) === 'medium', '60m should be medium');
  assert(durationBucket(2 * 60 * 60_000) === 'long', '2h should be long');

  const expires = retentionExpiry('1d', 0);
  assert(expires === new Date(86_400_000).toISOString(), '1d expiry should be deterministic');
});

Deno.test('language normalization accepts ISO hints and rejects arbitrary text', () => {
  assert(normalizeLanguage('EN-US') === 'en', 'EN-US should become en');
  assert(normalizeLanguage('tr_TR') === 'tr', 'tr_TR should become tr');
  assert(normalizeLanguage('Japanese') === 'auto', 'language names are not accepted as ISO hints');
});

Deno.test('remote URL validation blocks local and private network targets', () => {
  assert(isSafeRemoteMediaUrl('https://cdn.example.com/movie.mp4'), 'public https URL should pass');
  assert(!isSafeRemoteMediaUrl('file:///tmp/movie.mp4'), 'file URL must fail');
  assert(!isSafeRemoteMediaUrl('http://127.0.0.1/movie.mp4'), 'loopback must fail');
  assert(!isSafeRemoteMediaUrl('http://192.168.1.9/movie.mp4'), 'private IPv4 must fail');
  assert(!isSafeRemoteMediaUrl('http://10.0.0.5/movie.mp4'), 'private IPv4 must fail');
  assert(!isSafeRemoteMediaUrl('http://172.20.0.5/movie.mp4'), 'private IPv4 must fail');
});
