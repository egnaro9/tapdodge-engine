package com.seraphlight.tapdodgerush.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the generator's output to fixed values.
 *
 * <p>These numbers are the contract between the Android build and the browser build.
 * The engine previously used java.util.Random and the two platforms produced different
 * games from the same seed; {@code :web:parityCheck} shows them ending 9 to 6. A test
 * that only asserted "deterministic within one runtime" passed throughout. This asserts
 * the actual values, so a change to the arithmetic fails here rather than silently
 * making the two builds different games again.
 */
public class RngTest {

    @Test
    public void seedProducesTheRecordedSequence() {
        Rng r = new Rng(42);
        int[] ints = new int[4];
        for (int i = 0; i < ints.length; i++) ints[i] = r.nextInt(960);
        assertArrayEquals(new int[] {410, 123, 48, 404}, ints,
                "the generator's output changed — the browser build is now a different game");
    }

    @Test
    public void floatsAreRecordedToo() {
        Rng r = new Rng(7);
        float[] got = new float[3];
        for (int i = 0; i < got.length; i++) got[i] = r.nextFloat();
        assertEquals(0.73069900f, got[0], 1e-7f);
        assertEquals(0.63853765f, got[1], 1e-7f);
        assertEquals(0.74916959f, got[2], 1e-7f);
    }

    @Test
    public void floatsStayInRange() {
        Rng r = new Rng(3);
        for (int i = 0; i < 20_000; i++) {
            float f = r.nextFloat();
            assertTrue(f >= 0f && f < 1f, "out of range: " + f);
        }
    }

    @Test
    public void boundsAreRespectedIncludingPowersOfTwo() {
        Rng r = new Rng(11);
        for (int bound : new int[] {1, 2, 64, 960, 1000}) {
            for (int i = 0; i < 5_000; i++) {
                int v = r.nextInt(bound);
                assertTrue(v >= 0 && v < bound, "bound " + bound + " produced " + v);
            }
        }
    }
}
