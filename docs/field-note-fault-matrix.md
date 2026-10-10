# I wrote down which test would catch each bug. The one aimed straight at it was blind, and so was the fix I tried first

The [determinism note](field-note-determinism.md) ends with three checks over the same
engine. All three look like determinism checks. That raises a question the checks cannot
answer about themselves: do they cover different failure modes, or the same one three
times?

So I wrote down, before running anything, which check would catch which bug. Then I
injected the bugs.

## The four checks

| | what it does |
|---|---|
| **S** `theSameSeedProducesTheSameRun` | runs the engine twice in one runtime and compares |
| **G** `ParityTraceTest` | asserts a 600-frame, 11-checkpoint trace against a committed golden file |
| **D** `:web:parityCheck` | diffs the JVM trace against the **shipped TeaVM artifact's** trace |
| **U** `InterstitialCadenceTest` | a plain unit test on interstitial cadence, the footnote column |

## The method

[`matrix/run_matrix.sh`](../matrix/run_matrix.sh) (134 lines): apply patch, rebuild **both**
artifacts, run S, G, D and U independently, revert under a trap.

They have to run separately because `:web:parityCheck` declares `dependsOn :engine:test`. A
golden failure would stop the differential from ever running, and then the columns could not
be attributed: a blank cell would mean "did not run", not "did not fire". `ParityTraceTest`
writes its traces before it asserts, which is what makes the split possible.

Every cell below is the exit status of a real check against a really rebuilt pair of
artifacts. Not a judgement.

## The faults

- **F1** `Rng` delegates to `java.util.Random` instead of the written-out LCG. On the JVM
  this is behaviour-identical, because it *is* the documented algorithm; TeaVM's
  implementation is not the JDK's. The original bug, reproduced from source.
- **F2** `PLAYER_BOTTOM_MARGIN` 120f to 121f. A shared constant both builds compile.
- **F3** `INTERSTITIAL_EVERY` 3 to 4. Behaviour the trace did not record.

## The first run

| fault | S | G | D | U |
|---|---|---|---|---|
| baseline | pass | pass | pass | pass |
| F1 runtime-supplied RNG | pass | pass | **FAIL** | pass |
| F2 shared constant | pass | **FAIL** | pass | pass |
| F3 untraced cadence | pass | pass | pass | **pass, and I had predicted FAIL** |

Read the two rows that worked first. F1 is caught **only** by the differential, because S
compares the JVM to itself and the golden file's JVM side never moves. F2 is caught **only**
by the golden, because both builds move together and the differential sees agreement.
Neither check subsumes the other. That was the point, and it held.

## The row I got wrong

F3 moving nothing in S, G or D was half expected. The trace recorded frame, score, streak,
run state, player position and obstacles, but not `runsSinceInterstitial`. **Blind by
schema.** Fine.

What I did not expect was column U. `InterstitialCadenceTest` exists for exactly this
behaviour and it passed. Its assertions referenced `GameEngine.INTERSTITIAL_EVERY` on eight
lines, so when the fault moved the constant from 3 to 4, the test's expectation moved with
it. It looped `for (run = 1; run < INTERSTITIAL_EVERY; run++)` and then asserted "no cue
after INTERSTITIAL_EVERY runs". Both halves were defined by the thing under test.

**Blind by self-derived expectation.** The test measured internal consistency, not
correctness, and the only interesting way that constant can be wrong is the one way it could
not see.

So F3 had no catching check at all.

## Closing it, and a second prediction

I fixed both blindnesses in [`2b2bfb7`](https://github.com/egnaro9/tapdodge-engine/commit/2b2bfb7):

- The cadence test now carries a literal answer key, `CUE_AFTER_RUN = {false, false, true}`,
  instead of deriving its expectation from the constant. It references
  `INTERSTITIAL_EVERY` once now, in a comment explaining why it must not.
- The parity trace records `runsSinceInterstitial` on both sides of the differential, with a
  read-only accessor on the engine, the field in the JVM writer and the TeaVM state export,
  and an exact comparison in the trace comparator. The golden was regenerated in the same
  commit.

Then, **before re-running**, I wrote down what I expected:

> F3 flips to U=FAIL only, and G and D stay green. The scripted run dies once, so the
> counter reads 0 then 1 and never approaches any threshold. Recording it cannot make the
> trace-based checks see a threshold change the run never exercises.

## The re-run

| fault | S | G | D | U |
|---|---|---|---|---|
| baseline | pass | pass | pass | pass |
| F1 runtime-supplied RNG | pass | pass | **FAIL** | pass |
| F2 shared constant | pass | **FAIL** | pass | pass |
| F3 untraced cadence | pass | pass | pass | **FAIL** |

[`matrix/results/matrix.md`](../matrix/results/matrix.md), run stamped
`2026-08-02T17:46:08Z`. The prediction held, including the part that matters: adding the
field to the trace did **not** make G or D fire. Recording a counter does not help a check
whose input plan never drives that counter anywhere interesting.

## What this says that one run could not

Three distinct design decisions construct blindness, and they are independent:

1. **The schema.** The trace cannot report a field it does not carry.
2. **The expectation.** A test whose expected value is imported from production code
   measures internal consistency. If the expected value in an assertion comes from the code
   under test, the assertion cannot fail for the interesting reason.
3. **The input plan.** This is the one the first run could not show, and it only became
   visible after fixing the other two. The scripted run never approaches the cadence
   threshold, so no amount of recording makes a trace-based check sensitive to it. Coverage
   of a field is not coverage of a behaviour.

## The honest caveats

Three faults is not a coverage measurement. It is three worked examples chosen to separate
four checks. A real matrix would keep going until it stopped finding new residual rows, and
mine stopped when I ran out of faults I could think of.

The predictions were written in a commit message and a chat log, not a pre-registration
anyone can audit. Take the first miss as the evidence they were genuinely made in advance,
since it is the row I would have quietly dropped otherwise.

The cadence defect was in a test, not in shipped behaviour. The game's cadence is correct.
What shipped broken was the confidence.

## Reproducing it

```
matrix/run_matrix.sh
```

Patches in [`matrix/faults/`](../matrix/faults/), per-run comparator output and the full log
in [`matrix/results/`](../matrix/results/). The harness refuses to run against a dirty tree,
and checks that **before** arming its restore trap. The first version armed the trap first,
so refusing to run checked out `engine/src` and destroyed the uncommitted work the guard
existed to protect.
