// Mutation tests for the fault-matrix gate.
//
// verify_matrix.mjs is a monitor, and a monitor cannot fail on clean data. Running it
// against a good run proves only that it does not crash. So every test below plants one
// specific defect in an otherwise-valid run and asserts the gate reports it. The gate has
// been seen to fail, once per failure mode, which is the only reason to believe it would.
//
// The fixtures are literals on purpose. Reading them out of matrix/results/ would make the
// expectations derive from the run under test, which is the defect the whole matrix exists
// to document. The cost of literals is that they rot if run_matrix.sh changes its output
// format, and the mutation suite would stay green while the real parser broke. That gap is
// covered by the other half of the CI job, which runs the real harness and the real
// verifier over real output; the two halves are not redundant.
//
// Usage: node --test matrix/verify_matrix.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { verify, parseExpected, TABLE_HEADER, COLUMNS } from "./verify_matrix.mjs";

const ROOT = path.resolve(import.meta.dirname, "..");
const STAMP = "2026-08-02T17:46:08Z";
const NOW_MS = Date.parse(STAMP) + 60_000;

const CASES = ["baseline", "F1_runtime_supplied_rng", "F2_shared_constant", "F3_untraced_cadence"];
const PATCHES = CASES.slice(1);

const CLEAN_KEY = [
  "# a comment, and a blank line, both of which the parser must skip",
  "",
  ["case", ...COLUMNS].join("\t"),
  "baseline\tpass\tpass\tpass\tpass",
  "F1_runtime_supplied_rng\tpass\tpass\tFAIL\tpass",
  "F2_shared_constant\tpass\tFAIL\tpass\tpass",
  "F3_untraced_cadence\tpass\tpass\tpass\tFAIL",
  "",
].join("\n");

const md = (rows, { stamp = STAMP, header = TABLE_HEADER } = {}) =>
  [
    `# Fault-by-check matrix — run of ${stamp}`,
    "",
    "Every cell is the exit status of a real check against a really rebuilt pair of",
    "artifacts. S/G/D are the matrix; U is the footnote (see run_matrix.sh).",
    "",
    header,
    "|---|---|---|---|---|",
    ...rows.map((r) => `| ${r.join(" | ")} |`),
    "",
    "Comparator verdicts per case are in `*_comparator.txt`; full log in `run.log`.",
    "",
  ].join("\n");

const CLEAN_ROWS = [
  ["baseline", "pass", "pass", "pass", "pass"],
  ["F1_runtime_supplied_rng", "pass", "pass", "FAIL", "pass"],
  ["F2_shared_constant", "pass", "FAIL", "pass", "pass"],
  ["F3_untraced_cadence", "pass", "pass", "pass", "FAIL"],
];

const AGREE = "the JVM and browser builds agree across 11 checkpoints (largest float difference 0.0010, tolerance 0.01)";
const DISAGREE = [
  "the two builds disagree (109 differences):",
  "  frame 60 obstacle 0 x: jvm=405.426 browser=304.426 (off by 101.0000)",
].join("\n");
const gradleFailure = (n) =>
  [
    "",
    `${n} test${n === 1 ? "" : "s"} completed, ${n} failed`,
    "",
    "FAILURE: Build failed with an exception.",
    "",
    "* What went wrong:",
    "Execution failed for task ':engine:test'.",
    "> There were failing tests. See the report at: file:///REDACTED/tapdodge-engine/engine/build/reports/tests/test/index.html",
    "",
    "BUILD FAILED in 498ms",
  ].join("\n");

const log = (sections) =>
  CASES.map((c) => `== ${c} ==\n${sections[c] ?? ""}`).join("\n") + "\n";

const CLEAN_SECTIONS = {
  baseline: AGREE,
  F1_runtime_supplied_rng: DISAGREE,
  F2_shared_constant: `${gradleFailure(1)}\n${AGREE}`,
  F3_untraced_cadence: `${AGREE}\n${gradleFailure(3)}`,
};

const run = (over = {}) =>
  verify({
    expectedText: CLEAN_KEY,
    matrixMd: md(CLEAN_ROWS),
    runLog: log(CLEAN_SECTIONS),
    patchNames: PATCHES,
    nowMs: NOW_MS,
    ...over,
  });

// The control. If this ever goes red the suite is over-strict and every test below is
// meaningless, because a gate that rejects a good run tells you nothing when it rejects a
// bad one.
test("control: a clean run has no problems and compares the whole matrix", () => {
  const r = run();
  assert.deepEqual(r.problems, []);
  assert.equal(r.cellsCompared, 16);
  assert.equal(r.cases, 4);
});

// Each entry: name, the mutation, and a substring the report must contain. Asserting on
// the message and not merely on "something failed" is what stops a test from passing
// because of an unrelated problem the mutation happened to introduce.
const MUTANTS = [
  [
    "a check that stopped firing",
    { matrixMd: md(CLEAN_ROWS.map((r) => (r[0] === "F1_runtime_supplied_rng" ? [r[0], "pass", "pass", "pass", "pass"] : r))) },
    "F1_runtime_supplied_rng D: expected FAIL, got pass",
  ],
  [
    "a check that started firing",
    { matrixMd: md(CLEAN_ROWS.map((r) => (r[0] === "baseline" ? [r[0], "FAIL", "pass", "pass", "pass"] : r))) },
    "baseline S: expected pass, got FAIL",
  ],
  [
    "an ERROR cell, which is a broken harness and never a verdict",
    { matrixMd: md(CLEAN_ROWS.map((r) => (r[0] === "F2_shared_constant" ? [r[0], "pass", "ERROR", "pass", "pass"] : r))) },
    "F2_shared_constant G: ERROR",
  ],
  [
    "ERROR smuggled into the answer key itself",
    { expectedText: CLEAN_KEY.replace("F2_shared_constant\tpass\tFAIL", "F2_shared_constant\tpass\tERROR") },
    "ERROR is not a legal expectation",
  ],
  [
    "a dropped case row in the table",
    { matrixMd: md(CLEAN_ROWS.filter((r) => r[0] !== "F3_untraced_cadence")) },
    'matrix.md has no row for case "F3_untraced_cadence"',
  ],
  [
    "a truncated answer key, which would quietly stop checking a fault",
    {
      expectedText: CLEAN_KEY.split("\n").filter((l) => !l.startsWith("F3_untraced_cadence")).join("\n"),
    },
    "A patch with no key row is a fault nobody checks",
  ],
  [
    "a new fault patch that nobody added to the key",
    { patchNames: [...PATCHES, "F4_unwritten"] },
    "F4_unwritten",
  ],
  [
    "a renamed case",
    { matrixMd: md(CLEAN_ROWS.map((r) => (r[0] === "F2_shared_constant" ? ["F2_renamed", ...r.slice(1)] : r))) },
    "matrix.md covers",
  ],
  [
    "a reordered header, which would remap every cell",
    { matrixMd: md(CLEAN_ROWS, { header: "| fault | G (golden) | S (same-runtime) | D (differential) | U (cadence unit test) |" }) },
    "no row matching the expected table header exactly",
  ],
  [
    "verifying the committed August results instead of a fresh run",
    { nowMs: Date.parse(STAMP) + 86_400_000 },
    "verifying the committed results file compares nothing",
  ],
  [
    "a renamed test, so the filter matched nothing and the check never ran",
    {
      runLog: log({
        ...CLEAN_SECTIONS,
        F3_untraced_cadence: `${AGREE}\n> No tests found for given includes: [com.seraphlight.tapdodgerush.engine.InterstitialCadenceTest](--tests filter)`,
      }),
    },
    "a --tests filter matched nothing",
  ],
  [
    "the zero-test guard firing",
    { runLog: log({ ...CLEAN_SECTIONS, baseline: `${AGREE}\nno tests were executed` }) },
    "zero-test guard fired",
  ],
  [
    "a FAIL cell with no failing-test evidence, the shape a compile error takes",
    { runLog: log({ ...CLEAN_SECTIONS, F3_untraced_cadence: AGREE }) },
    "this cell is unproven",
  ],
  [
    "a FAIL cell whose counts say nothing actually failed",
    {
      runLog: log({
        ...CLEAN_SECTIONS,
        F2_shared_constant: `${AGREE}\n1 test completed, 0 failed\n> There were failing tests.`,
      }),
    },
    "so no test actually failed",
  ],
  [
    "a test failure in a case the key says is all green",
    { runLog: log({ ...CLEAN_SECTIONS, baseline: `${AGREE}\n${gradleFailure(2)}` }) },
    "the key expects S/G/U all green",
  ],
  [
    "the comparator contradicting the D cell",
    { runLog: log({ ...CLEAN_SECTIONS, F1_runtime_supplied_rng: AGREE }) },
    "the key says FAIL but the comparator said",
  ],
  [
    "the differential leaving no verdict at all",
    { runLog: log({ ...CLEAN_SECTIONS, baseline: "" }) },
    "no comparator verdict",
  ],
  [
    "both verdicts in one section",
    { runLog: log({ ...CLEAN_SECTIONS, baseline: `${AGREE}\n${DISAGREE}` }) },
    "both an agree and a disagree",
  ],
  [
    "a missing log section, so a row has no evidence behind it",
    { runLog: `== baseline ==\n${AGREE}\n` },
    "there is no evidence behind that row",
  ],
  [
    "a table with a header and no rows",
    { matrixMd: md([]) },
    "no case rows",
  ],
  [
    "a row with a cell knocked out",
    { matrixMd: md(CLEAN_ROWS).replace("| F2_shared_constant | pass | FAIL | pass | pass |", "| F2_shared_constant | pass | FAIL | pass |") },
    "expected 5 cells, got 4",
  ],
  [
    "a key row with a cell knocked out",
    { expectedText: CLEAN_KEY.replace("F2_shared_constant\tpass\tFAIL\tpass\tpass", "F2_shared_constant\tpass\tFAIL\tpass") },
    "need 5 whitespace-separated fields",
  ],
  [
    "a missing run stamp, so staleness cannot be judged",
    { matrixMd: md(CLEAN_ROWS).replace(/^# Fault-by-check matrix.*$/m, "# some other title") },
    'run of <timestamp>" title',
  ],
  [
    "an unparseable run stamp",
    { matrixMd: md(CLEAN_ROWS, { stamp: "whenever" }) },
    "is not a parseable timestamp",
  ],
];

for (const [name, mutation, expectedSubstring] of MUTANTS) {
  test(`caught: ${name}`, () => {
    const { problems } = run(mutation);
    assert.notEqual(problems.length, 0, "the gate stayed green on a planted defect");
    assert.ok(
      problems.some((p) => p.includes(expectedSubstring)),
      `no problem mentioned ${JSON.stringify(expectedSubstring)}. Got:\n${problems.map((p) => `  - ${p}`).join("\n")}`
    );
  });
}

// The committed key is a published claim: docs/field-note-fault-matrix.md prints these
// same cells, and so does the dev.to article. This asserts the file on disk is well formed
// and still covers the patches that exist, independently of any run.
test("the committed answer key parses and matches the patches on disk", async () => {
  const text = await readFile(path.join(ROOT, "matrix/expected.tsv"), "utf8");
  const { rows, problems } = parseExpected(text);
  assert.deepEqual(problems, []);

  const patches = (await readdir(path.join(ROOT, "matrix/faults")))
    .filter((f) => f.endsWith(".patch"))
    .map((f) => f.slice(0, -".patch".length))
    .sort();
  assert.deepEqual(rows.map((r) => r.name), ["baseline", ...patches]);
});

// run_matrix.sh writes the header this verifier matches verbatim. If someone edits the
// harness's table header, the gate would stop finding the table and say so at runtime, but
// only on a machine that can run a full matrix. This catches it in unit time.
test("the harness still writes the table header the verifier matches", async () => {
  const sh = await readFile(path.join(ROOT, "matrix/run_matrix.sh"), "utf8");
  assert.ok(
    sh.includes(TABLE_HEADER),
    `run_matrix.sh no longer emits the header verify_matrix.mjs matches:\n  ${TABLE_HEADER}`
  );
});

// The answer key is a published claim, not just a build input: docs/field-note-fault-matrix.md
// prints these same sixteen cells under "## The re-run", and so does the dev.to article
// built from it. Without this, updating the key to match a changed run turns CI green and
// silently makes the published write-up wrong. The failure message in verify_matrix.mjs
// tells a human to update both, but a note does not stop anything.
//
// Matched by position rather than by name, because the note writes the cases in prose
// ("F1 runtime-supplied RNG") while the key uses the patch basenames.
test("the field note's re-run table still publishes the cells in the answer key", async () => {
  const note = await readFile(path.join(ROOT, "docs/field-note-fault-matrix.md"), "utf8");
  const after = note.split(/^## The re-run\s*$/m)[1];
  assert.ok(after, 'docs/field-note-fault-matrix.md has no "## The re-run" section');

  const lines = after.split("\n").map((l) => l.trim());
  const h = lines.findIndex((l) => /^\|\s*fault\s*\|/.test(l));
  assert.notEqual(h, -1, "the re-run section has no results table");

  const published = [];
  for (let i = h + 2; i < lines.length; i++) {
    if (!lines[i].startsWith("|")) break;
    published.push(
      lines[i].split("|").slice(1, -1).map((s) => s.replaceAll("*", "").trim())
    );
  }

  const { rows } = parseExpected(await readFile(path.join(ROOT, "matrix/expected.tsv"), "utf8"));
  assert.equal(
    published.length,
    rows.length,
    `the note publishes ${published.length} rows, the key has ${rows.length}`
  );
  for (const [i, row] of rows.entries()) {
    assert.deepEqual(
      published[i].slice(1),
      COLUMNS.map((c) => row.cells[c]),
      `row ${i + 1} of the note's re-run table ("${published[i][0]}") disagrees with the key row for ${row.name}`
    );
  }
});
