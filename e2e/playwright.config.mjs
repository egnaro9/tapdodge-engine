// E2E config for the browser game.
//
// The suite runs against .site/ — assembled by `npm run site` (wired as pretest),
// which calls the real tools/deploy_site.sh including :web:check, so the
// differential oracle has already agreed before any browser test runs.
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  timeout: 30_000,
  retries: 0, // a flake should fail loudly, not be retried into silence
  workers: 1, // one shared static server, one game at a time — keeps timing honest
  forbidOnly: !!process.env.CI,
  use: {
    baseURL: "http://127.0.0.1:4173",
    viewport: { width: 480, height: 900 },
    hasTouch: true,
    trace: "retain-on-failure",
  },
  webServer: {
    command: "node serve.mjs",
    url: "http://127.0.0.1:4173/index.html",
    // Reusing a stray server means testing whatever build owns the port —
    // the critic demonstrated a green run against an artifact not in the repo.
    reuseExistingServer: false,
    timeout: 15_000,
    stdout: "pipe",
    stderr: "pipe",
  },
});
