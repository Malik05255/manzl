import baseHandler, { SmartMediaWorkflow } from "./index-v3";
import { cleanupExpiredMovies } from "./retention-cleanup";

export { SmartMediaWorkflow };

interface Env {
  MOVIES: R2Bucket;
}

export default {
  fetch: baseHandler.fetch,

  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      cleanupExpiredMovies(env)
        .then((summary) => console.log("smart-media retention cleanup", summary))
        .catch((error) => console.error("smart-media retention cleanup failed", error)),
    );
  },
} satisfies ExportedHandler<Env>;
