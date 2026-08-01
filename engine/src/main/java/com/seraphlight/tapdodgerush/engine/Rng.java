package com.seraphlight.tapdodgerush.engine;

/**
 * A seeded generator whose output is defined here rather than by the platform.
 *
 * <p>The engine originally used {@link java.util.Random}. On the JVM and in the
 * TeaVM-compiled browser build, the same seed and the same inputs produced
 * <em>different games</em>: in the run {@code :web:parityCheck} drives, the browser's player
 * was already dead by frame 360 while the JVM's was alive and went on to score three more.
 * {@code java.util.Random}'s algorithm is specified, but the engine was relying on
 * whichever implementation the runtime supplied, and they do not agree.
 *
 * <p>This is the linear congruential generator {@code java.util.Random} documents,
 * written out so both builds execute the same arithmetic. Determinism is the point:
 * a seed has to name one game, or a recorded run cannot be replayed and the two
 * builds cannot be checked against each other.
 */
public final class Rng {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private long seed;

    public Rng(long seed) {
        this.seed = (seed ^ MULTIPLIER) & MASK;
    }

    private int next(int bits) {
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    /** Uniform in {@code [0, bound)}. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        if ((bound & -bound) == bound) {          // a power of two
            return (int) ((bound * (long) next(31)) >> 31);
        }
        int bits, val;
        do {                                       // reject the biased tail
            bits = next(31);
            val = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }

    /** Uniform in {@code [0, 1)}. */
    public float nextFloat() {
        return next(24) / ((float) (1 << 24));
    }
}
