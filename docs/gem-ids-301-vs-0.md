# Field note: 301 duplicate gem IDs in the browser, 0 on the JVM

*A worked example of the differential check this engine gets for free by running two ways.*

This engine runs on a JVM (where the tests and CI live) and in the browser, compiled to
JavaScript by [TeaVM](https://teavm.org) (see [`demo-js/`](../../demo-js)). The same Java on
two runtimes is a free correctness check: **where the two disagree, one of them is wrong** — no
gold answer required, just a disagreement to notice.

## The bug

Every gem's identity key was built from the clock:

```java
String id = row + "-" + col + "-" + System.nanoTime() + "-" + RNG.nextInt(1000);
```

IDs are how the renderer tells gems apart — two gems with the same id animate as one. Running
the same board generation on both runtimes and counting collisions over 128,000 gems:

| Runtime | Duplicate IDs |
| --- | --- |
| JVM | **0** |
| Browser (TeaVM) | **301** |

Same source, same inputs, different answer. That disagreement *is* the signal — a machine
counted it; nobody had to guess that something felt off.

## Which side was lying, and why

`System.nanoTime()` looks unique but only leans on the clock being high-resolution enough that
two calls land on different values. A JVM's timer is fine, so the flaw was invisible there.
Browsers deliberately clamp their clock to ~100µs (a Spectre mitigation), so `nanoTime` barely
advances between gems and `RNG.nextInt(1000)` collides often enough to matter. Neither runtime
was broken — the **code** was, for depending on clock resolution it was never promised. The
browser was just honest about it.

## The fix

A monotonic counter, unconditionally unique on every clock:

```java
private static long idSeq = 0L;
private static synchronized long nextId() { return idSeq++; }
```

```diff
-String id = row + "-" + col + "-" + System.nanoTime() + "-" + RNG.nextInt(1000);
+String id = row + "-" + col + "-" + nextId();
```

See [`BoardEngine.java`](../../src/main/java/io/github/egnaro9/match3/BoardEngine.java) — the id
scheme's Javadoc records the same reasoning at the source.

## The receipt

A fix isn't done because someone says "fixed" — it's done when a check that would catch the bug
is on disk and passing. [`IdUniquenessTest`](../../src/test/java/io/github/egnaro9/match3/IdUniquenessTest.java)
(3 tests) fails if an id ever leans on the clock again, including a hot-loop test that mints
gems as fast as possible — a clock-derived id collides there on any platform whose timer
doesn't advance between calls.

---

Full write-up, including a second case where the same "run it two ways, look for a quiet
disagreement" habit turned up a bug in the *compiler* rather than the code (a one-character fix
[merged into TeaVM](https://github.com/konsoletyper/teavm/pull/1213)):
**[301 duplicate IDs in the browser, 0 on the JVM](https://dev.to/egnaro9/301-duplicate-ids-in-the-browser-0-on-the-jvm-one-real-bug-end-to-end-10cj)** (dev.to).
