package com.seraphlight.tapdodgerush.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Produces the JVM half of the cross-compiler differential check, and pins it.
 *
 * <p>Why this exists: {@code theSameSeedProducesTheSameRun} compares the JVM to itself, and it
 * passed the entire time the two builds were producing <em>different games</em> — this run
 * ends 9 to 6 across the two, and they disagree from frame 60 on, because {@code java.util.Random} is
 * specified but the engine was running whichever implementation the runtime supplied. A test
 * that only ever consults one runtime cannot notice that. This one writes a trace the other
 * runtime is then made to reproduce.
 *
 * <p>Two things are checked, and they catch different failures:
 * <ul>
 *   <li>the trace matches {@code parity-golden.json}, so a change to the rules has to be a
 *       deliberate act with a visible diff rather than a silent drift;
 *   <li>{@code tools/compare_trace.mjs} then diffs this trace against the one the TeaVM
 *       build produces in Node, which is the actual cross-platform assertion.
 * </ul>
 *
 * <p>The input plan is written to disk rather than duplicated in JavaScript. If both sides
 * hardcoded the drags, a typo in one would make the traces differ for a reason that has
 * nothing to do with the engine, and the check would be measuring the test instead.
 */
public class ParityTraceTest {

    private static final Path OUT = Path.of("build", "parity");
    private static final Path GOLDEN =
            Path.of("src", "test", "resources", "parity-golden.json");

    @Test
    public void theTraceMatchesTheGoldenFileAndIsWrittenForTheBrowserToReproduce()
            throws IOException {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve("inputs.json"), inputPlan());

        String trace = runTrace();
        Files.writeString(OUT.resolve("jvm-trace.json"), trace);

        assertTrue(Files.exists(GOLDEN),
                "no golden trace at " + GOLDEN.toAbsolutePath()
                        + " — write build/parity/jvm-trace.json there to accept the current rules");

        assertEquals(Files.readString(GOLDEN).trim(), trace.trim(),
                "the engine no longer produces the recorded run. If the rules changed on purpose, "
                        + "copy build/parity/jvm-trace.json over " + GOLDEN + " in the same commit.");
    }

    /** The exact input script, emitted so the Node side drives the engine identically. */
    private static String inputPlan() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"seed\":").append(ParityTrace.SEED)
          .append(",\"width\":").append(ParityTrace.WIDTH)
          .append(",\"height\":").append(ParityTrace.HEIGHT)
          .append(",\"frames\":").append(ParityTrace.FRAMES)
          .append(",\"frameMs\":").append(ParityTrace.FRAME_MS)
          .append(",\"drags\":[");
        for (int i = 0; i < ParityTrace.DRAG_FRAMES.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"frame\":").append(ParityTrace.DRAG_FRAMES[i])
              .append(",\"x\":").append(ParityTrace.TARGETS[i]).append('}');
        }
        sb.append("],\"checkpoints\":[");
        for (int i = 0; i < ParityTrace.CHECKPOINTS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ParityTrace.CHECKPOINTS[i]);
        }
        return sb.append("]}").toString();
    }

    private static String runTrace() {
        GameEngine e = new GameEngine(new Rng(ParityTrace.SEED));
        e.setBounds(ParityTrace.WIDTH, ParityTrace.HEIGHT);
        e.start(0L);

        List<String> rows = new ArrayList<>();
        for (int f = 1; f <= ParityTrace.FRAMES; f++) {
            float drag = ParityTrace.dragAt(f);
            if (!Float.isNaN(drag)) {
                e.setTargetX(drag);
            }
            e.update((long) (f * ParityTrace.FRAME_MS), ParityTrace.FRAME_MS);
            if (ParityTrace.isCheckpoint(f)) {
                rows.add(checkpoint(f, e));
            }
        }
        return "[\n  " + String.join(",\n  ", rows) + "\n]\n";
    }

    /**
     * One checkpoint as JSON.
     *
     * <p>Numbers are written to three decimals. The two compilers do not agree bit-for-bit —
     * TeaVM's float arithmetic rounds slightly differently from the JVM's — so pinning full
     * precision would fail on rounding rather than on behaviour. Three decimals is far tighter
     * than the divergence this check is for: the bug it was built to catch moved obstacles by
     * hundreds of pixels and changed the score.
     */
    private static String checkpoint(int frame, GameEngine e) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"frame\":").append(frame)
          .append(",\"score\":").append(e.score())
          .append(",\"streak\":").append(e.streak())
          .append(",\"running\":").append(e.isRunning())
          .append(",\"over\":").append(e.isGameOver())
          .append(",\"runs\":").append(e.runsSinceInterstitial())
          .append(",\"px\":").append(f3(e.playerX()))
          .append(",\"py\":").append(f3(e.playerY()))
          .append(",\"obstacles\":[");
        boolean first = true;
        for (GameEngine.Obstacle o : e.obstacles()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(f3(o.x)).append(',').append(f3(o.y))
              .append(',').append(f3(o.size)).append(']');
        }
        return sb.append("]}").toString();
    }

    private static String f3(float v) {
        return String.format("%.3f", v);
    }
}
