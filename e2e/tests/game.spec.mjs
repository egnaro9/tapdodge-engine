// The interaction is the thing under test: every input is a real pointer, touch or
// key event, the path a player's finger takes. The oracle is the engine's own
// state() through the frozen window.__tapdodge seam — the seam exposes ONLY state,
// so a test cannot cause state through it even lazily.
//
// Oracles assert convergence to expected values, not mere direction: a cold-critic
// mutation pass showed direction-only assertions stayed green while pointerX() was
// collapsed to 0 and the game was unplayable. Where a number is asserted, it is a
// literal derived from the page's own contract (1080-unit board), not read back
// from the code under test.
import { test, expect } from "@playwright/test";

const W = 1080; // board units, from the page's canvas contract (index.html)

const state = (page) =>
  page.evaluate(() => JSON.parse(window.__tapdodge.state()));

const canvasBox = (page) => page.locator("#game").boundingBox();

// Real mouse tap at canvas-relative fractional coordinates.
const tap = async (page, fx = 0.5, fy = 0.5) => {
  const box = await canvasBox(page);
  await page.mouse.move(box.x + box.width * fx, box.y + box.height * fy);
  await page.mouse.down();
  await page.mouse.up();
};

// Held-button drag from fx0 to fx1 in steps, then release.
const drag = async (page, fx0, fx1, fy = 0.7, steps = 8) => {
  const box = await canvasBox(page);
  await page.mouse.move(box.x + box.width * fx0, box.y + box.height * fy);
  await page.mouse.down();
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(
      box.x + box.width * (fx0 + ((fx1 - fx0) * i) / steps),
      box.y + box.height * fy);
    await page.waitForTimeout(40);
  }
  await page.mouse.up();
};

const startRun = async (page) => {
  await tap(page);
  await page.waitForFunction(() => JSON.parse(window.__tapdodge.state()).running);
};

// The player converges on the target under easing — poll until |px - want| < tol.
const expectPxNear = (page, want, tol = 60) =>
  page.waitForFunction(
    ([w, t]) => Math.abs(JSON.parse(window.__tapdodge.state()).px - w) < t,
    [want, tol], { timeout: 6_000 });

test.beforeEach(async ({ page }) => {
  await page.goto("/index.html");
  await page.waitForFunction(() => !!window.__tapdodge);
});

test("loads idle, renders something, and requests no audio before a gesture", async ({ page }) => {
  const audioRequests = [];
  page.on("request", (r) => { if (r.url().includes("bgm")) audioRequests.push(r.url()); });
  await expect(page.locator("#game")).toBeVisible();
  const s = await state(page);
  expect(s.started).toBe(false);
  expect(s.running).toBe(false);

  // the canvas is not blank: the idle screen draws text and the player block
  const uniform = await page.evaluate(() => {
    const c = document.getElementById("game");
    const d = c.getContext("2d").getImageData(0, 0, c.width, c.height).data;
    const first = [d[0], d[1], d[2]];
    for (let i = 4; i < d.length; i += 4) {
      if (d[i] !== first[0] || d[i + 1] !== first[1] || d[i + 2] !== first[2]) return false;
    }
    return true;
  });
  expect(uniform).toBe(false);

  await page.waitForTimeout(500);
  expect(audioRequests).toHaveLength(0);
});

test("a tap OUTSIDE the canvas does nothing; a tap on it starts the run", async ({ page }) => {
  // click the page heading, well outside #game
  await page.locator("h1").click();
  await page.waitForTimeout(300);
  expect((await state(page)).started).toBe(false);

  await tap(page);
  await page.waitForFunction(() => JSON.parse(window.__tapdodge.state()).running);
  const s = await state(page);
  expect(s.started).toBe(true);
  expect(s.over).toBe(false);
  expect(s.score).toBe(0);
});

test("dragging converges the player on the pointer's board position, both directions", async ({ page }) => {
  await startRun(page);
  await drag(page, 0.5, 0.25);
  await expectPxNear(page, 0.25 * W); // literal expectation, not read from the code
  await drag(page, 0.25, 0.75);
  await expectPxNear(page, 0.75 * W);
});

test("a hovering mouse with no button held does NOT steer", async ({ page }) => {
  await startRun(page);
  const before = (await state(page)).px;
  const box = await canvasBox(page);
  for (let i = 0; i <= 6; i++) {
    await page.mouse.move(box.x + box.width * (i / 6), box.y + box.height * 0.5);
    await page.waitForTimeout(50);
  }
  await page.waitForTimeout(400);
  const after = (await state(page)).px;
  expect(Math.abs(after - before)).toBeLessThan(30);
});

test("both arrow keys steer, in their own directions", async ({ page }) => {
  await startRun(page);
  const start = (await state(page)).px;
  for (let i = 0; i < 6; i++) { await page.keyboard.press("ArrowRight"); await page.waitForTimeout(60); }
  await page.waitForFunction(
    (px0) => JSON.parse(window.__tapdodge.state()).px > px0 + 30, start, { timeout: 5_000 });
  const right = (await state(page)).px;
  for (let i = 0; i < 6; i++) { await page.keyboard.press("ArrowLeft"); await page.waitForTimeout(60); }
  await page.waitForFunction(
    (px1) => JSON.parse(window.__tapdodge.state()).px < px1 - 30, right, { timeout: 5_000 });
});

test("space starts a run from the keyboard, as the page's hint promises", async ({ page }) => {
  await page.keyboard.press(" ");
  await page.waitForFunction(
    () => JSON.parse(window.__tapdodge.state()).running, null, { timeout: 5_000 });
  expect((await state(page)).score).toBe(0);
});

test("an unattended run ends; game over ignores steering; best persists and restores", async ({ page }) => {
  test.setTimeout(45_000);
  await startRun(page);
  await page.waitForFunction(
    () => JSON.parse(window.__tapdodge.state()).over, null, { timeout: 20_000 });
  const s = await state(page);
  expect(s.running).toBe(false);

  // terminal means terminal: arrows and hover must not resume or move anything
  await page.keyboard.press("ArrowRight");
  await page.waitForTimeout(300);
  const still = await state(page);
  expect(still.over).toBe(true);
  expect(still.px).toBe(s.px);

  // best persisted exactly, and a fresh page restores it into the engine
  const best = await page.evaluate(() => localStorage.getItem("tapdodge:best"));
  expect(Number(best)).toBe(s.best);
  await page.reload();
  await page.waitForFunction(() => !!window.__tapdodge);
  expect((await state(page)).best).toBe(s.best);

  // a deliberate tap after game over starts a NEW run
  await tap(page);
  await page.waitForFunction(
    () => JSON.parse(window.__tapdodge.state()).running, null, { timeout: 5_000 });
  const s2 = await state(page);
  expect(s2.score).toBe(0);
  expect(s2.over).toBe(false);
});

test("touch: tap starts, touch-drag steers (the shipped platform is a phone)", async ({ page }) => {
  const box = await canvasBox(page);
  await page.touchscreen.tap(box.x + box.width * 0.5, box.y + box.height * 0.5);
  await page.waitForFunction(
    () => JSON.parse(window.__tapdodge.state()).running, null, { timeout: 5_000 });
  // touch-drag left via CDP-backed touchscreen taps along a path
  for (let i = 1; i <= 6; i++) {
    await page.touchscreen.tap(
      box.x + box.width * (0.5 - (0.3 * i) / 6), box.y + box.height * 0.7);
    await page.waitForTimeout(60);
  }
  await expectPxNear(page, 0.2 * W, 120);
});

test("mute toggles both ways, persists both values, and survives reload", async ({ page }) => {
  const mute = page.locator("#mute");
  await expect(mute).toHaveAttribute("aria-pressed", "false"); // page default
  await mute.click();
  await expect(mute).toHaveAttribute("aria-pressed", "true");
  await expect(mute).toHaveText(/off/);
  expect(await page.evaluate(() => localStorage.getItem("tapdodge:muted"))).toBe("1");

  await page.reload();
  await page.waitForFunction(() => !!window.__tapdodge);
  await expect(mute).toHaveAttribute("aria-pressed", "true"); // restored, not default

  await mute.click();
  await expect(mute).toHaveAttribute("aria-pressed", "false");
  await expect(mute).toHaveText(/on/);
  expect(await page.evaluate(() => localStorage.getItem("tapdodge:muted"))).toBe("0");
});

test("assets load via the version-stamped URLs, not the cache-busting fallback", async ({ page }) => {
  const urls = [];
  page.on("request", (r) => urls.push(r.url()));
  await page.goto("/index.html");
  await page.waitForFunction(() => !!window.__tapdodge);
  const stamped = urls.filter((u) => /\?v=[0-9a-f]{12}/.test(u));
  const fallback = urls.filter((u) => /\?t=\d+/.test(u));
  expect(stamped.length).toBeGreaterThanOrEqual(2); // game.js and tapdodge.js
  expect(fallback).toHaveLength(0);
});
