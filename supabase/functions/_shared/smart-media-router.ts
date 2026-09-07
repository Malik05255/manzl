export type Retention = 'none' | '1d' | '7d' | '30d' | 'permanent';
export type SourceKind = 'url' | 'local';

export type RoutePlan = {
  sourceKind: SourceKind;
  sourceLanguage: string;
  targetLanguage: 'ar';
  asrPrimary: 'whisper-large-v3' | 'whisper-large-v3-turbo';
  asrFallback: 'whisper-large-v3' | 'whisper-large-v3-turbo';
  translationOrder: string[];
  reviewProvider: string;
  analysisProvider: string;
  retention: Retention;
  expiresAt: string | null;
  rationale: string[];
};

export function normalizeRetention(value: unknown): Retention {
  const v = String(value || 'none').toLowerCase();
  return v === '1d' || v === '7d' || v === '30d' || v === 'permanent' ? v : 'none';
}

export function retentionExpiry(retention: Retention, now = Date.now()): string | null {
  const days = retention === '1d' ? 1 : retention === '7d' ? 7 : retention === '30d' ? 30 : 0;
  return days ? new Date(now + days * 86_400_000).toISOString() : null;
}

export function durationBucket(durationMs: number): string {
  if (!Number.isFinite(durationMs) || durationMs <= 0) return 'unknown';
  if (durationMs <= 20 * 60_000) return 'short';
  if (durationMs <= 90 * 60_000) return 'medium';
  return 'long';
}

export function makeRoutePlan(input: {
  sourceKind: SourceKind;
  durationMs?: number;
  language?: string;
  retention?: unknown;
}): RoutePlan {
  const durationMs = Math.max(0, Number(input.durationMs || 0));
  const bucket = durationBucket(durationMs);
  const language = normalizeLanguage(input.language);
  const retention = normalizeRetention(input.retention);

  // Quality is the hard gate. Large V3 remains primary when language is unknown,
  // audio is long, or the language commonly benefits from the stronger model.
  const qualityFirst = language === 'auto' || bucket === 'long' || ['ja', 'hi', 'ko', 'zh'].includes(language);
  const asrPrimary = qualityFirst ? 'whisper-large-v3' : 'whisper-large-v3-turbo';
  const asrFallback = asrPrimary === 'whisper-large-v3' ? 'whisper-large-v3-turbo' : 'whisper-large-v3';

  return {
    sourceKind: input.sourceKind,
    sourceLanguage: language,
    targetLanguage: 'ar',
    asrPrimary,
    asrFallback,
    translationOrder: ['azure', 'groq'],
    reviewProvider: 'groq:gpt-oss-120b',
    analysisProvider: 'groq:gpt-oss-120b',
    retention,
    expiresAt: retentionExpiry(retention),
    rationale: [
      `duration:${bucket}`,
      `language:${language}`,
      qualityFirst ? 'quality-first-asr' : 'speed-first-with-quality-fallback',
      retention === 'none' ? 'zero-copy-playback' : `retention:${retention}`,
      'zero-bill-route-only',
    ],
  };
}

export function normalizeLanguage(value: unknown): string {
  const raw = String(value || 'auto').trim().toLowerCase();
  if (!raw || raw === 'auto') return 'auto';
  const iso = raw.split(/[-_]/)[0];
  return /^[a-z]{2,3}$/.test(iso) ? iso : 'auto';
}

export function isSafeRemoteMediaUrl(value: unknown): boolean {
  try {
    const url = new URL(String(value || ''));
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return false;
    const host = url.hostname.toLowerCase();
    if (!host || host === 'localhost' || host.endsWith('.local')) return false;
    if (/^(127\.|10\.|192\.168\.|169\.254\.)/.test(host)) return false;
    if (/^172\.(1[6-9]|2\d|3[01])\./.test(host)) return false;
    return true;
  } catch {
    return false;
  }
}
