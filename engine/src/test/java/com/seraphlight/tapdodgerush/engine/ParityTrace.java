package com.seraphlight.tapdodgerush.engine;

/**
 * The scripted run both builds must agree on.
 *
 * <p>This class exists so the JVM test and the Node script are provably driving the engine
 * the same way. It is the <em>input</em> half of the differential check: a fixed seed, a
 * fixed frame cadence, and a fixed sequence of drags. The output half is a trace of engine
 * state, compared across the two compilers by {@code tools/compare_trace.mjs}.
 *
 * <p>The numbers here are arbitrary but must never change casually — changing them changes
 * what the golden trace means.
 */
public final class ParityTrace {

    public static final long SEED = 42L;
    public static final int WIDTH = 1080;
    public static final int HEIGHT = 1920;
    public static final int FRAMES = 600;
    public static final float FRAME_MS = 16f;

    /** Frame numbers at which the player is dragged, paired with {@link #TARGETS}. */
    public static final int[] DRAG_FRAMES = {30, 75, 120, 180, 240, 300, 360, 420, 480, 540};
    public static final float[] TARGETS = {200, 900, 540, 100, 980, 300, 760, 540, 60, 1020};

    /**
     * Frames at which the full state is recorded.
     *
     * <p>Weighted towards live play: this run dies at about frame 420, and checkpoints after
     * that would all record the same frozen state. The last one is kept deliberately, so the
     * two builds also have to agree that game over is terminal and identical.
     */
    public static final int[] CHECKPOINTS = {30, 60, 90, 120, 150, 200, 250, 300, 360, 420, 600};

    private ParityTrace() {}

    /** True if {@code frame} is a checkpoint. */
    public static boolean isCheckpoint(int frame) {
        for (int c : CHECKPOINTS) {
            if (c == frame) return true;
        }
        return false;
    }

    /** The drag target for {@code frame}, or {@code Float.NaN} if the frame has no drag. */
    public static float dragAt(int frame) {
        for (int i = 0; i < DRAG_FRAMES.length; i++) {
            if (DRAG_FRAMES[i] == frame) return TARGETS[i];
        }
        return Float.NaN;
    }
}
