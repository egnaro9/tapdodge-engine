// Diffs the JVM trace against the browser trace and fails loudly on any disagreement.
//
// Numbers are compared with a tolerance rather than as strings. The two compilers do not
// produce bit-identical floats — the last of three printed decimals can differ by one — and
// a string diff would report that as a failure of the engine when it is a property of IEEE
// rounding. Everything that is not a float (score, streak, running, over, obstacle count)
// is compared exactly, because there is no rounding excuse for those.
//
// Usage: node tools/compare_trace.mjs <jvm-trace.json> <js-trace.json>
import { readFile } from "node:fs/promises";

// Pixels. Set from measurement, not taste: across this 600-frame run the largest observed
// disagreement is 0.002px, on two obstacle x-values, from float32 rounding accumulating over
// ~300 frames of drift. This allows 5x that. It is still four orders of magnitude below the
// bug the check exists for — swapping the seeded generator back to java.util.Random moves
// obstacles by hundreds of pixels and changes the final score, which this catches loudly.
const TOLERANCE = 0.01;

const [, , jvmPath, jsPath] = process.argv;
if (!jvmPath || !jsPath) {
  console.error("usage: node tools/compare_trace.mjs <jvm-trace.json> <js-trace.json>");
  process.exit(2);
}

const jvm = JSON.parse(await readFile(jvmPath, "utf8"));
const js = JSON.parse(await readFile(jsPath, "utf8"));

const problems = [];
const exact = (where, a, b) => {
  if (a !== b) problems.push(`${where}: jvm=${a} browser=${b}`);
};
const near = (where, a, b) => {
  const d = Math.abs(Number(a) - Number(b));
  if (!(d <= TOLERANCE)) {
    problems.push(`${where}: jvm=${a} browser=${b} (off by ${d.toFixed(4)})`);
  }
};

exact("checkpoint count", jvm.length, js.length);

let maxDrift = 0;
for (let i = 0; i < Math.min(jvm.length, js.length); i++) {
  const a = jvm[i];
  const b = js[i];
  const at = `frame ${a.frame}`;

  exact(`${at} frame`, a.frame, b.frame);
  for (const k of ["score", "streak", "running", "over"]) {
    exact(`${at} ${k}`, a[k], b[k]);
  }
  near(`${at} px`, a.px, b.px);
  maxDrift = Math.max(maxDrift, Math.abs(Number(a.px) - Number(b.px)));

  exact(`${at} obstacle count`, a.obstacles.length, b.obstacles.length);
  for (let o = 0; o < Math.min(a.obstacles.length, b.obstacles.length); o++) {
    for (const [j, axis] of ["x", "y", "size"].entries()) {
      near(`${at} obstacle ${o} ${axis}`, a.obstacles[o][j], b.obstacles[o][j]);
      maxDrift = Math.max(
        maxDrift,
        Math.abs(Number(a.obstacles[o][j]) - Number(b.obstacles[o][j]))
      );
    }
  }
}

if (problems.length) {
  console.error(`the two builds disagree (${problems.length} differences):`);
  for (const p of problems.slice(0, 25)) console.error(`  ${p}`);
  if (problems.length > 25) console.error(`  … and ${problems.length - 25} more`);
  process.exit(1);
}

console.log(
  `the JVM and browser builds agree across ${jvm.length} checkpoints ` +
    `(largest float difference ${maxDrift.toFixed(4)}, tolerance ${TOLERANCE})`
);
