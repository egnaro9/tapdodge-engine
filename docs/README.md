# docs/

**`diverged-trace.json`** — what the browser build produced *before* the fix.

It is a real recording, not a synthetic one: `Rng` was reverted to `java.util.Random`, the
engine recompiled with TeaVM, and the resulting trace captured. It ships here so the
falsification is reproducible without anyone having to break the code themselves:

```bash
./gradlew :engine:test                      # writes engine/build/parity/jvm-trace.json
node tools/compare_trace.mjs \
  engine/build/parity/jvm-trace.json \
  docs/diverged-trace.json
```

```
the two builds disagree (109 differences):
  frame 60 obstacle 0 x: jvm=405.426 browser=304.426 (off by 101.0000)
  frame 90 obstacle 1 x: jvm=438.177 browser=74.177 (off by 364.0000)
  frame 150 obstacle 3 x: jvm=765.438 browser=266.438 (off by 499.0000)
  frame 200 score: jvm=1 browser=2
  … and 105 more
```

The JVM side of that comparison is the *current* engine — which is the point. The JVM's output
never changed when the bug was introduced or removed, because the hand-written LCG produces
exactly what the JVM's `java.util.Random` produces. Only the browser moved. A golden file of
the JVM trace passes in both states; only the cross-runtime diff fails.
