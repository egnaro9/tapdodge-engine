# tapdodge-engine

The rules engine behind [Tap Dodge Rush](https://play.google.com/store/apps/details?id=com.seraphlight.tapdodgerush),
compiled twice: once by `javac` for the Android app, once by [TeaVM](https://teavm.org) for the
browser. **[Play the browser build](https://egnaro9.github.io/seraphlight-studios/tap-dodge-rush/play/).**

The interesting part is not that it compiles to two targets. It is that a test can tell you
when the two targets stop agreeing — and that when I wrote that test, they had already stopped.

## The bug this repo is shaped around

`GameEngine` used `java.util.Random`. Its algorithm is specified down to the constants, so a
seed ought to name exactly one game. It did not. Same seed, same inputs, the run this repo's
check drives — reproducible below by reverting `Rng` and running `:web:parityCheck`:

| | JVM | browser |
|---|---|---|
| first obstacle x, frame 60 | 405.426 | 304.426 |
| still alive at frame 360 | yes | no |
| final score | 9 | 6 |

The engine was not running the specified algorithm. It was running whichever implementation
the *runtime* supplied, and TeaVM's is not the JVM's. [`Rng`](engine/src/main/java/com/seraphlight/tapdodgerush/engine/Rng.java)
now writes out the linear congruential generator `java.util.Random` documents, so both builds
execute the same arithmetic instead of trusting that they will.

The existing test for this was `theSameSeedProducesTheSameRun`. It passed the entire time.
It runs the engine twice **in the same runtime** and compares the results — a shape that
cannot observe a disagreement between runtimes, no matter how large.

## The check that does work

```bash
./gradlew :web:parityCheck
```

```
the JVM and browser builds agree across 11 checkpoints (largest float difference 0.0020, tolerance 0.01)
```

Three pieces, arranged so neither side can quietly drift:

1. [`ParityTraceTest`](engine/src/test/java/com/seraphlight/tapdodgerush/engine/ParityTraceTest.java)
   runs a scripted 600-frame game on the JVM — fixed seed, fixed frame cadence, ten scripted
   drags — and records score, streak, run state, player and every obstacle at 11 checkpoints.
   It asserts that trace against a committed golden file, so changing the rules requires a
   visible diff.
2. It writes the **input plan** to disk, and [`tools/browser_trace.mjs`](tools/browser_trace.mjs)
   reads that file to drive the run. The drags are defined once. If both sides hardcoded them,
   a typo in one would look like an engine disagreement.
3. [`tools/compare_trace.mjs`](tools/compare_trace.mjs) diffs the two traces. Score, streak,
   run state and obstacle counts must match **exactly**. Floats are compared to 0.01px,
   which is 5× the largest rounding difference actually observed between the two compilers
   and ~10,000× smaller than the bug above.

The Node script imports `web/build/generated/teavm/js/tapdodge.js` — the artifact the web
build ships, not a re-implementation of it.

Publishing goes through [`tools/deploy_site.sh`](tools/deploy_site.sh), which runs that check
before it copies anything and stamps the engine import with a content hash. Without the stamp
a rebuilt engine lands at a URL the browser already has cached, so a visitor gets a fresh page
running an old engine — which happened on this page's first deploy and looked convincingly
like a bug that was not there.

The full story, with the two blind tests and what replaced them, is in
[`docs/field-note-determinism.md`](docs/field-note-determinism.md).

### It was falsified before being trusted

Reverting `Rng` to `java.util.Random` and re-running — or, without touching any code, diffing against the recording of that build committed at [`docs/diverged-trace.json`](docs/diverged-trace.json):

```
the two builds disagree (69 differences):
  frame 60  obstacle 0 x: jvm=405.426 browser=304.426 (off by 101.0000)
  frame 90  obstacle 1 x: jvm=438.177 browser=74.177  (off by 364.0000)
  …
  frame 250 obstacle 3 x: jvm=879.507 browser=167.507 (off by 712.0000)
  frame 360 score:        jvm=8       browser=6
  frame 360 running:      jvm=true    browser=false
  … and 44 more
```
(excerpted — the comparator prints the first 25 and a count)

The golden-file assertion **passed** during that same run. It had to: the JVM's
`java.util.Random` produces exactly what the hand-written LCG produces, so the JVM trace was
unchanged and only the browser moved. Pinning one runtime's output cannot find a
cross-runtime defect. Only the diff between runtimes can.

## Layout

| | |
|---|---|
| `engine/` | the rules. Plain Java 11 and JUnit 5, **no main-source dependency outside the JDK** |
| `web/` | the `@JSExport` wrapper and TeaVM config — kept out of `engine/` so that guarantee holds |
| `tools/` | the browser half of the differential check |

`engine/` having nothing on its classpath is what makes one rulebook possible. Add Android to
it and the browser build stops compiling; add TeaVM and the Android build carries a compiler
it never uses. The split is load-bearing, not tidiness:

```bash
grep -rn "android\|teavm" engine/src/main   # no matches
```

## What is not here

The Android app — renderer, sound, haptics, ads, the rewarded-ad continue — is closed
source. This repo is the part the two builds share.

The browser build has no continue for that reason: the shipped game sells one through a
rewarded ad, there is no ad here, and handing out free continues would make a web score mean
something different from a phone score. `canUseContinue()` is simply never called.
