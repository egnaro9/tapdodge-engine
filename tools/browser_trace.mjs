// Drives the TeaVM-compiled engine through the input plan the JVM test wrote, and prints
// the resulting trace in the same shape.
//
// This is the browser half of the differential check. It runs the artifact the web build
// actually ships — web/build/generated/teavm/js/tapdodge.js — not a re-implementation, so a
// disagreement here means the two compilers disagree about the rules.
//
// Usage: node tools/browser_trace.mjs <tapdodge.js> <inputs.json>
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const [, , enginePath, inputsPath] = process.argv;
if (!enginePath || !inputsPath) {
  console.error("usage: node tools/browser_trace.mjs <tapdodge.js> <inputs.json>");
  process.exit(2);
}

const plan = JSON.parse(await readFile(inputsPath, "utf8"));
const engine = await import(pathToFileURL(enginePath).href);

const dragByFrame = new Map(plan.drags.map((d) => [d.frame, d.x]));
const checkpoints = new Set(plan.checkpoints);

engine.create(plan.seed, plan.width, plan.height);
engine.start(0);

// Three decimals, matching ParityTraceTest#f3. toFixed is the same round-half-away-from-zero
// as Java's %.3f for these magnitudes, and the comparator allows a one-ulp last-digit
// difference anyway — see tools/compare_trace.mjs.
const f3 = (v) => v.toFixed(3);

const rows = [];
for (let f = 1; f <= plan.frames; f++) {
  if (dragByFrame.has(f)) engine.setTargetX(dragByFrame.get(f));
  engine.update(f * plan.frameMs, plan.frameMs);
  if (checkpoints.has(f)) {
    const s = JSON.parse(engine.state());
    rows.push(
      `{"frame":${f},"score":${s.score},"streak":${s.streak},` +
        `"running":${s.running},"over":${s.over},"px":${f3(s.px)},"py":${f3(s.py)},` +
        `"obstacles":[${s.obstacles.map(([x, y, size]) => `[${f3(x)},${f3(y)},${f3(size)}]`).join(",")}]}`
    );
  }
}

process.stdout.write(`[\n  ${rows.join(",\n  ")}\n]\n`);
