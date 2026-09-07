type CleanupEnv = {
  MOVIES: R2Bucket;
};

type CleanupSummary = {
  scanned: number;
  deleted: number;
  skipped: number;
  capped: boolean;
};

const MAX_OBJECTS_PER_RUN = 5_000;

/**
 * Deletes only temporary movie objects under ttl/*.
 * Translation text/summary records in Supabase are intentionally preserved.
 */
export async function cleanupExpiredMovies(env: CleanupEnv, now = Date.now()): Promise<CleanupSummary> {
  let cursor: string | undefined;
  let scanned = 0;
  let deleted = 0;
  let skipped = 0;
  let capped = false;

  do {
    const page = await env.MOVIES.list({
      prefix: "ttl/",
      limit: Math.min(1_000, MAX_OBJECTS_PER_RUN - scanned),
      cursor,
      include: ["customMetadata"],
    });

    if (!page.objects.length) break;

    const expiredKeys: string[] = [];
    for (const object of page.objects) {
      scanned += 1;
      const metadata = object.customMetadata || {};
      const expiresAt = parseTime(metadata.expires_at) ?? deriveExpiry(metadata.retention, metadata.created_at);
      if (expiresAt != null && expiresAt <= now) expiredKeys.push(object.key);
      else skipped += 1;

      if (scanned >= MAX_OBJECTS_PER_RUN) {
        capped = true;
        break;
      }
    }

    if (expiredKeys.length) {
      // R2 supports deleting up to 1,000 keys per call.
      await env.MOVIES.delete(expiredKeys);
      deleted += expiredKeys.length;
    }

    if (capped || !page.truncated) break;
    cursor = page.cursor;
  } while (cursor);

  return { scanned, deleted, skipped, capped };
}

function deriveExpiry(retention: string | undefined, createdAt: string | undefined): number | null {
  const created = parseTime(createdAt);
  if (created == null) return null;
  const days = retention === "1d" ? 1 : retention === "7d" ? 7 : retention === "30d" ? 30 : 0;
  return days ? created + days * 86_400_000 : null;
}

function parseTime(value: string | undefined): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}
