# My determinism test passed for months while the two builds played different games

I compiled the rules engine of a shipped Android game to the browser. Same Java, two
compilers. Then I checked whether the two agreed.

They did not — and the test I already had for exactly this had been green the whole time.

## The setup

Tap Dodge Rush is a small arcade game on Google Play. The rules live in one module with no
Android on its classpath, which is what let me compile them a second time with
[TeaVM](https://teavm.org) and run the same logic on a canvas in a browser tab.

A seeded run should be reproducible. Give the engine seed 42 and a fixed sequence of inputs,
and you should get the same game every time — that is what makes a run replayable and two
builds comparable.

Here is what I actually got, same seed, same inputs:

| | JVM | browser |
|---|---|---|
| first obstacle x, frame 60 | 405.426 | 304.426 |
| still alive at frame 360 | yes | no |
| final score | 9 | 6 |

Not a rounding difference. A different game.

## The cause is boring. The test failure is not.

`GameEngine` used `java.util.Random`. Its algorithm is specified down to the constants — you
can read the exact linear congruential generator in the Javadoc. So a seed ought to name
exactly one sequence.

But my code was not running that algorithm. It was running *whichever implementation the
runtime supplied*, and TeaVM's is not the JVM's. The specification describes what
`java.util.Random` does; it does not force a foreign runtime's reimplementation to match.

The fix took ten minutes: write the LCG out longhand so both builds execute the same
arithmetic instead of trusting that they will.

The interesting part is the test.

## The test that could not have caught it

I had a test called `theSameSeedProducesTheSameRun`. It ran the engine twice, with the same
seed, and asserted the results matched. It passed on every commit, including every commit
during which the browser build was playing a different game.

It had to pass. It runs the engine twice **in the same runtime**. A test shaped like that
cannot observe a disagreement *between* runtimes — not a subtle one, not a 712-pixel one. The
assertion was true and useless at the same time.

This is the part I keep coming back to: the test was not weak, or flaky, or under-specified.
It was **structurally incapable** of failing for this reason. No amount of making it stricter
would have helped.

## A golden file would not have saved me either

The obvious next move is to pin the output: record a known-good trace, commit it, assert
against it forever.

I did that too. Then I reverted the fix to see what would happen.

The golden-file assertion **passed**.

It had to. The JVM's `java.util.Random` produces exactly what my hand-written LCG produces —
that is the whole point of writing out the documented algorithm. So the JVM's trace never
moved. Only the browser's did, and the golden file had nothing to say about the browser.

Pinning one runtime's output is blind in precisely the same way as comparing one runtime to
itself. Both feel like determinism tests. Neither can see across the boundary they claim to
hold across.

## What actually works

The only thing that finds this is a diff between the two runtimes.

1. A JVM test drives a scripted 600-frame game — fixed seed, fixed frame cadence, ten scripted
   drags — and records score, streak, run state, the player and every obstacle at 11
   checkpoints.
2. It writes the **input plan** to disk, and a Node script reads *that file* to drive its run.
   The drags are defined once. If both sides hardcoded them, a typo in one would look like an
   engine disagreement, and I would be debugging the test instead of the code.
3. The Node script imports the artifact the web build actually ships — not a reimplementation
   of it — and a comparator diffs the traces. Score, streak, run state and obstacle counts must
   match exactly. Floats get 0.01px, which is 5× the largest rounding difference the two
   compilers actually produce and four orders of magnitude below the bug it exists for.

```
the JVM and browser builds agree across 11 checkpoints
(largest float difference 0.0020, tolerance 0.01)
```

And I falsified it before trusting it. Revert `Rng`, run it again:

```
the two builds disagree (69 differences):
  frame 90  obstacle 1 x: jvm=438.177 browser=74.177  (off by 364.0000)
  frame 250 obstacle 3 x: jvm=879.507 browser=167.507 (off by 712.0000)
  frame 360 score:        jvm=8       browser=6
  frame 360 running:      jvm=true    browser=false
```

## Two more things fell out of it

**The test I wrote to cover the fix was itself vacuous at first.** Its precondition needed a
run that survived long enough to earn a continue, and a parked player never gets there — so it
silently took a branch that asserted nothing. It passed with the fix removed. I only noticed
because I make a habit of breaking the code to confirm the test goes red. Now it plays the run
with a dodging strategy, and removing the fix fails it for the right reason.

**Recording px but not py hid a real change.** I altered a positioning constant by one pixel to
test something unrelated, and the entire suite stayed green — because the trace captured the
player's horizontal position and not its vertical one. A trace only protects what it records.

## The thing worth taking away

If you have a test whose name contains "deterministic," "reproducible," or "same seed," ask it
one question: *which runtimes does it consult?*

If the answer is one, it cannot tell you the thing its name implies. Neither can a golden file
of that one runtime's output. Only the diff between them can.

---

The engine is open source: [github.com/egnaro9/tapdodge-engine](https://github.com/egnaro9/tapdodge-engine)
— the check is in `tools/compare_trace.mjs`, and the README has the numbers.

You can [play the browser build](https://egnaro9.github.io/seraphlight-studios/tap-dodge-rush/play/),
which is the artifact the test drives. The Android version is
[on Google Play](https://play.google.com/store/apps/details?id=com.seraphlight.tapdodgerush).
