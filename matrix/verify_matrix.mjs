// Checks a fault-matrix run against the committed answer key in matrix/expected.tsv.
//
// This exists because matrix/run_matrix.sh cannot fail. It runs the checks, writes their
// exit codes into a table, and then exits 0 whatever those codes were. Wiring the harness
// into CI and reading its exit status would therefore assert nothing at all: the job would
// be green with every check blind, which is the exact failure the matrix was built to
// document. Worse, three of the sixteen cells are SUPPOSED to be FAIL, so a renamed test,
// a compile error or a dead node step satisfies them. "Something went wrong" and "the
// differential fired" are the same exit code.
//
// So this compares against an independent literal, and it does not trust the exit code to
// mean what the cell says. For every FAIL it demands the evidence of the right KIND of
// failure, and for the D column it reads the comparator's own sentence out of the log.
//
// The predicate is exported as verify() and takes strings, not paths, so that
// verify_matrix.test.mjs can plant defects in it and watch it go red. A gate nobody has
// seen fail is a gate nobody has tested.
//
// Usage: node matrix/verify_matrix.mjs [--max-age-seconds N]
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";

export const COLUMNS = ["S", "G", "D", "U"];
export const GRADLE_COLUMNS = ["S", "G", "U"];

// The exact header run_matrix.sh writes. Compared verbatim rather than by column count so
// that reordering the columns cannot silently remap every cell in the table.
export const TABLE_HEADER =
  "| fault | S (same-runtime) | G (golden) | D (differential) | U (cadence unit test) |";

// Default staleness window. The committed matrix/results/matrix.md is stamped August and
// is a published artifact the field note cites, so a verifier that happily read it would
// print green forever without running anything.
const DEFAULT_MAX_AGE_SECONDS = 3600;

// tools/compare_trace.mjs ends on exactly one of these. Note that "agree" is a substring
// of "disagree", so both patterns are anchored on enough words to be exclusive.
const D_PASSED = /the JVM and browser builds agree across (\d+) checkpoints/;
const D_FAILED = /the two builds disagree \((\d+) differences\)/;

// Gradle's words for a real test failure, as opposed to any other nonzero exit.
const REAL_TEST_FAILURE = /There were failing tests/;
const TEST_COUNTS = /(\d+) tests? completed, (\d+) failed/;

// Two ways a check can report a verdict without having run. Both are fatal wherever they
// appear: a filter that matches nothing exits 1, which run_matrix.sh renders as "FAIL",
// which is indistinguishable from the check firing. The second is :engine:test's own
// doLast guard, added when this module was briefly configured to run zero tests and go
// green anyway.
const NEVER_RAN = [
  [/No tests found for given includes[^\n]*/, "a --tests filter matched nothing, so that check never ran"],
  [/no tests were executed/, "the :engine:test zero-test guard fired, so that check never ran"],
];

const LEGAL_CELLS = new Set(["pass", "FAIL"]);

const splitFields = (line) => line.trim().split(/\s+/);

export function parseExpected(text) {
  const problems = [];
  const rows = [];
  let sawHeader = false;

  for (const [i, raw] of text.split("\n").entries()) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const fields = splitFields(line);
    if (!sawHeader) {
      sawHeader = true;
      if (fields.join(" ") !== ["case", ...COLUMNS].join(" ")) {
        problems.push(
          `expected.tsv line ${i + 1}: header must be "case ${COLUMNS.join(" ")}", got "${line}"`
        );
      }
      continue;
    }
    if (fields.length !== COLUMNS.length + 1) {
      problems.push(
        `expected.tsv line ${i + 1}: need ${COLUMNS.length + 1} whitespace-separated fields, got ${fields.length} ("${line}")`
      );
      continue;
    }
    const [name, ...cells] = fields;
    for (const [j, cell] of cells.entries()) {
      if (!LEGAL_CELLS.has(cell)) {
        problems.push(
          cell === "ERROR"
            ? `expected.tsv line ${i + 1}: ERROR is not a legal expectation (column ${COLUMNS[j]}, case ${name}). ERROR means the harness broke; a key that accepts it lets a broken harness satisfy the gate.`
            : `expected.tsv line ${i + 1}: cell "${cell}" is not pass or FAIL (column ${COLUMNS[j]}, case ${name})`
        );
      }
    }
    rows.push({ name, cells: Object.fromEntries(COLUMNS.map((c, j) => [c, cells[j]])) });
  }

  if (!sawHeader) problems.push("expected.tsv has no header row");
  return { rows, problems };
}

export function parseMatrixTable(md) {
  const problems = [];
  const lines = md.split("\n");

  // Deliberately not matching the em-dash in the harness's title, so a change of
  // punctuation there cannot break the staleness guard.
  const stamp = md.match(/^#\s+Fault-by-check matrix\b.*\brun of\s+(\S+)\s*$/m);
  if (!stamp) problems.push('matrix.md has no "# Fault-by-check matrix ... run of <timestamp>" title');

  const h = lines.findIndex((l) => l.trim() === TABLE_HEADER);
  if (h === -1) {
    problems.push(`matrix.md has no row matching the expected table header exactly:\n  ${TABLE_HEADER}`);
    return { stamp: stamp?.[1], rows: [], problems };
  }
  if (!/^\|[-|\s]+\|$/.test(lines[h + 1]?.trim() ?? "")) {
    problems.push(`matrix.md line ${h + 2}: expected a markdown separator row under the header`);
  }

  const rows = [];
  for (let i = h + 2; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith("|")) break;
    const fields = line.split("|").slice(1, -1).map((s) => s.trim());
    if (fields.length !== COLUMNS.length + 1) {
      problems.push(`matrix.md line ${i + 1}: expected ${COLUMNS.length + 1} cells, got ${fields.length}`);
      continue;
    }
    const [name, ...cells] = fields;
    rows.push({ name, cells: Object.fromEntries(COLUMNS.map((c, j) => [c, cells[j]])), line: i + 1 });
  }

  if (!rows.length) problems.push("matrix.md table has a header but no case rows");
  return { stamp: stamp?.[1], rows, problems };
}

// Splits run.log into the "== name ==" sections run_case writes.
export function parseLogSections(log) {
  const sections = new Map();
  let current = null;
  for (const line of log.split("\n")) {
    const m = line.match(/^==\s+(\S+)\s+==\s*$/);
    if (m) {
      current = m[1];
      sections.set(current, []);
      continue;
    }
    if (current) sections.get(current).push(line);
  }
  return new Map([...sections].map(([k, v]) => [k, v.join("\n")]));
}

/**
 * @param {object} input
 * @param {string} input.expectedText  contents of matrix/expected.tsv
 * @param {string} input.matrixMd      contents of matrix/results/matrix.md
 * @param {string} input.runLog        contents of matrix/results/run.log
 * @param {string[]} input.patchNames  basenames of matrix/faults/*.patch, without .patch
 * @param {number} input.nowMs         wall clock, injected so the test suite is not flaky
 * @param {number} [input.maxAgeSeconds]
 */
export function verify({ expectedText, matrixMd, runLog, patchNames, nowMs, maxAgeSeconds = DEFAULT_MAX_AGE_SECONDS }) {
  const problems = [];
  let cellsCompared = 0;

  const expected = parseExpected(expectedText);
  const table = parseMatrixTable(matrixMd);
  problems.push(...expected.problems, ...table.problems);

  // 1. The key must enumerate exactly the cases that exist on disk, in run order. The key
  //    is not allowed to be the only list of what gets checked: a fault patch with no row
  //    here would otherwise drop out of the gate silently.
  const onDisk = ["baseline", ...[...patchNames].sort()];
  const inKey = expected.rows.map((r) => r.name);
  if (inKey.join(",") !== onDisk.join(",")) {
    problems.push(
      `expected.tsv lists [${inKey.join(", ")}] but matrix/faults/ plus baseline is [${onDisk.join(", ")}].\n` +
        "  A patch with no key row is a fault nobody checks; a key row with no patch is a case the harness cannot run."
    );
  }

  // 2. The table must cover the same cases in the same order.
  const inTable = table.rows.map((r) => r.name);
  if (inTable.join(",") !== inKey.join(",")) {
    problems.push(`matrix.md covers [${inTable.join(", ")}]; expected.tsv expects [${inKey.join(", ")}]`);
  }

  // 3. Staleness. Catches a verifier pointed at the committed August artifact instead of a
  //    fresh run, which would compare nothing and report green.
  if (table.stamp) {
    const ranMs = Date.parse(table.stamp);
    if (Number.isNaN(ranMs)) {
      problems.push(`matrix.md run stamp "${table.stamp}" is not a parseable timestamp`);
    } else {
      const ageSeconds = Math.round((nowMs - ranMs) / 1000);
      if (ageSeconds > maxAgeSeconds) {
        problems.push(
          `matrix.md is stamped ${table.stamp}, which is ${ageSeconds}s old (limit ${maxAgeSeconds}s). ` +
            "Run matrix/run_matrix.sh first; verifying the committed results file compares nothing."
        );
      }
    }
  }

  // 4. Nothing anywhere in the log may say a check did not run.
  for (const [pattern, why] of NEVER_RAN) {
    const hit = runLog.match(pattern);
    if (hit) problems.push(`run.log: ${why}\n  ${hit[0].trim()}`);
  }

  const sections = parseLogSections(runLog);
  const byName = new Map(table.rows.map((r) => [r.name, r]));

  for (const want of expected.rows) {
    const got = byName.get(want.name);
    if (!got) {
      problems.push(`matrix.md has no row for case "${want.name}"`);
      continue;
    }

    // 5. The cells themselves.
    for (const col of COLUMNS) {
      cellsCompared++;
      if (got.cells[col] === "ERROR") {
        problems.push(
          `${want.name} ${col}: ERROR. run_matrix.sh reports ERROR (exit 3) when the harness itself ` +
            "broke, so this is never a verdict and never satisfies the key."
        );
        continue;
      }
      if (got.cells[col] !== want.cells[col]) {
        problems.push(
          `${want.name} ${col}: expected ${want.cells[col]}, got ${got.cells[col]} (matrix.md line ${got.line})`
        );
      }
    }

    const section = sections.get(want.name);
    if (section === undefined) {
      problems.push(`run.log has no "== ${want.name} ==" section, so there is no evidence behind that row`);
      continue;
    }

    // 6. The D column, from the comparator's own sentence. This is the one column with
    //    positive evidence for BOTH verdicts, because compare_trace.mjs prints a distinct
    //    line either way.
    const dPassed = D_PASSED.exec(section);
    const dFailed = D_FAILED.exec(section);
    if (dPassed && dFailed) {
      problems.push(`${want.name} D: run.log contains both an agree and a disagree verdict`);
    } else if (!dPassed && !dFailed) {
      problems.push(
        `${want.name} D: run.log carries no comparator verdict, so the differential's cell has no evidence. ` +
          "Expected one line from tools/compare_trace.mjs."
      );
    } else {
      const fromLog = dFailed ? "FAIL" : "pass";
      if (fromLog !== want.cells.D) {
        problems.push(
          `${want.name} D: the key says ${want.cells.D} but the comparator said "${(dFailed ?? dPassed)[0]}"`
        );
      }
    }

    // 7. Gradle FAILs must be real test failures. Without this, a renamed test or a
    //    compile error satisfies F2 G=FAIL and F3 U=FAIL, and the gate stays green while
    //    the matrix means nothing.
    const wantsGradleFailure = GRADLE_COLUMNS.some((c) => want.cells[c] === "FAIL");
    const sawFailureText = REAL_TEST_FAILURE.test(section);
    const counts = TEST_COUNTS.exec(section);
    if (wantsGradleFailure) {
      const which = GRADLE_COLUMNS.filter((c) => want.cells[c] === "FAIL").join("/");
      if (!sawFailureText) {
        problems.push(
          `${want.name} ${which}: expected FAIL, and the cell says FAIL, but run.log never says ` +
            '"There were failing tests". A nonzero gradle exit from a build error or a missing test ' +
            "renders as FAIL too, so this cell is unproven."
        );
      }
      if (!counts) {
        problems.push(`${want.name} ${which}: run.log has no "N tests completed, M failed" line`);
      } else if (Number(counts[2]) < 1) {
        problems.push(`${want.name} ${which}: run.log reports ${counts[0]}, so no test actually failed`);
      }
    } else if (sawFailureText) {
      problems.push(
        `${want.name}: the key expects S/G/U all green, but run.log says "There were failing tests"` +
          (counts ? ` (${counts[0]})` : "")
      );
    }
  }

  // 8. A gate that compared nothing must not read green.
  const owed = expected.rows.length * COLUMNS.length;
  if (expected.rows.length < 4) {
    problems.push(`expected.tsv defines only ${expected.rows.length} cases; the published matrix has 4`);
  }
  if (cellsCompared !== owed) {
    problems.push(`compared ${cellsCompared} cells, owed ${owed}. The verifier did not evaluate the whole matrix.`);
  }

  return { problems, cellsCompared, cases: expected.rows.length };
}

// ---- CLI ----

if (import.meta.url === `file://${process.argv[1]}`) {
  const argv = process.argv.slice(2);
  const ageFlag = argv.indexOf("--max-age-seconds");
  const maxAgeSeconds = ageFlag === -1 ? DEFAULT_MAX_AGE_SECONDS : Number(argv[ageFlag + 1]);
  if (!Number.isFinite(maxAgeSeconds) || maxAgeSeconds < 0) {
    console.error("usage: node matrix/verify_matrix.mjs [--max-age-seconds N]");
    process.exit(2);
  }

  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
  const read = async (p) => {
    try {
      return await readFile(path.join(root, p), "utf8");
    } catch (e) {
      console.error(`cannot read ${p}: ${e.code ?? e.message}`);
      console.error("Run matrix/run_matrix.sh first; this verifies a run, it does not perform one.");
      process.exit(2);
    }
  };

  const [expectedText, matrixMd, runLog] = await Promise.all([
    read("matrix/expected.tsv"),
    read("matrix/results/matrix.md"),
    read("matrix/results/run.log"),
  ]);

  let patchNames;
  try {
    patchNames = (await readdir(path.join(root, "matrix/faults")))
      .filter((f) => f.endsWith(".patch"))
      .map((f) => f.slice(0, -".patch".length));
  } catch (e) {
    console.error(`cannot list matrix/faults: ${e.code ?? e.message}`);
    process.exit(2);
  }

  const { problems, cellsCompared, cases } = verify({
    expectedText,
    matrixMd,
    runLog,
    patchNames,
    nowMs: Date.now(),
    maxAgeSeconds,
  });

  if (problems.length) {
    console.error(`the fault matrix does not match matrix/expected.tsv (${problems.length} problems):`);
    for (const p of problems) console.error(`  - ${p}`);
    console.error(
      "\nIf the run is right and the key is stale, update matrix/expected.tsv AND the tables in " +
        "docs/field-note-fault-matrix.md, which publish these cells."
    );
    process.exit(1);
  }

  console.log(
    `the fault matrix matches the answer key: ${cellsCompared} cells across ${cases} cases, ` +
      `every FAIL backed by its own evidence in run.log`
  );
}
