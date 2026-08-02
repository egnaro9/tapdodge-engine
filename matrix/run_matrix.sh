#!/usr/bin/env bash
# Fault-by-check matrix runner.
#
# For each fault patch, this applies the patch to the engine source, rebuilds BOTH
# artifacts, runs each check independently, records pass/fail, and reverts. Every cell
# in the published matrix is the exit code of a real run against a real build — not a
# claim about one.
#
# The checks:
#   S  same-runtime determinism   GameEngineTest.theSameSeedProducesTheSameRun
#   G  golden file                ParityTraceTest vs src/test/resources/parity-golden.json
#   D  differential               node drives the TeaVM artifact through the input plan
#                                 the JVM test wrote; comparator diffs the traces
#
# S, G and D are run separately on purpose. The repo's own :web:parityCheck depends on
# :engine:test, so a fault that fails G would stop D from ever running and the two
# columns could not be attributed independently. G writes inputs.json and jvm-trace.json
# BEFORE its assertion, so D always has fresh traces even when G is red.
#
# U is a footnote column, not part of the matrix: InterstitialCadenceTest is the unit
# test added when the F3 class of bug was found. It shows the residual was closed by a
# check from a different family, not by widening the trace.
#
# Usage: matrix/run_matrix.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RESULTS_DIR=matrix/results
mkdir -p "$RESULTS_DIR"
OUT_MD="$RESULTS_DIR/matrix.md"
LOG="$RESULTS_DIR/run.log"
: > "$LOG"

JS=web/build/generated/teavm/js/tapdodge.js
PARITY=engine/build/parity

# The dirty check runs BEFORE the trap is armed. The first version armed the
# trap first, so refusing to run on a dirty tree checked out engine/src and
# destroyed the uncommitted work the guard existed to protect.
if [ -n "$(git status --porcelain -- engine/src web/src)" ]; then
  echo "refusing to run: engine/src or web/src is dirty" >&2
  exit 2
fi

# Never leave a fault applied, whatever happens.
restore() { git checkout -- engine/src 2>/dev/null || true; }
trap restore EXIT

# ---- the individual checks; each echoes PASS/FAIL and returns the real exit ----

run_S() {
  ./gradlew -q :engine:test --rerun \
    --tests 'com.seraphlight.tapdodgerush.engine.GameEngineTest.theSameSeedProducesTheSameRun' \
    >>"$LOG" 2>&1
}

run_G() {
  ./gradlew -q :engine:test --rerun \
    --tests 'com.seraphlight.tapdodgerush.engine.ParityTraceTest' \
    >>"$LOG" 2>&1
}

run_D() {
  ./gradlew -q :web:generateJavaScript >>"$LOG" 2>&1 || return 3
  node tools/browser_trace.mjs "$JS" "$PARITY/inputs.json" \
    > "$PARITY/js-trace.json" 2>>"$LOG" || return 3
  node tools/compare_trace.mjs "$PARITY/jvm-trace.json" "$PARITY/js-trace.json" \
    >>"$LOG" 2>&1
}

run_U() {
  ./gradlew -q :engine:test --rerun \
    --tests 'com.seraphlight.tapdodgerush.engine.InterstitialCadenceTest' \
    >>"$LOG" 2>&1
}

cell() { # exit code -> table cell
  case "$1" in
    0) echo "pass" ;;
    3) echo "ERROR" ;;   # harness failure, not a verdict — never report as FAIL
    *) echo "FAIL" ;;
  esac
}

declare -a ROWS

run_case() { # name, patch (empty = baseline)
  local name="$1" patch="$2"
  echo "== $name ==" | tee -a "$LOG"

  if [ -n "$patch" ]; then
    git apply "$patch" || { echo "  patch failed to apply: $patch" >&2; exit 2; }
  fi

  run_S; local s=$?
  run_G; local g=$?
  run_D; local d=$?
  run_U; local u=$?

  # keep the comparator's own words for this case
  tail -5 "$LOG" | grep -E "agree|disagree" | tail -1 \
    > "$RESULTS_DIR/${name}_comparator.txt" || true

  restore
  ROWS+=("| $name | $(cell $s) | $(cell $g) | $(cell $d) | $(cell $u) |")
  echo "  S=$(cell $s) G=$(cell $g) D=$(cell $d) U=$(cell $u)"
}

run_case "baseline" ""
run_case "F1_runtime_supplied_rng" "matrix/faults/F1_runtime_supplied_rng.patch"
run_case "F2_shared_constant"      "matrix/faults/F2_shared_constant.patch"
run_case "F3_untraced_cadence"     "matrix/faults/F3_untraced_cadence.patch"

{
  echo "# Fault-by-check matrix — run of $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "Every cell is the exit status of a real check against a really rebuilt pair of"
  echo "artifacts. S/G/D are the matrix; U is the footnote (see run_matrix.sh)."
  echo
  echo "| fault | S (same-runtime) | G (golden) | D (differential) | U (cadence unit test) |"
  echo "|---|---|---|---|---|"
  for r in "${ROWS[@]}"; do echo "$r"; done
  echo
  echo "Comparator verdicts per case are in \`*_comparator.txt\`; full log in \`run.log\`."
} > "$OUT_MD"

echo
echo "wrote $OUT_MD"
