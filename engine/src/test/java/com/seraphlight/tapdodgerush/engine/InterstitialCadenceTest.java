package com.seraphlight.tapdodgerush.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How often an interstitial may interrupt the player.
 *
 * <p>The cadence lives in the engine so it is a rule with a test rather than a counter in a
 * click handler that nobody reads back. This is what stops it quietly becoming "every run" the
 * next time someone is looking at revenue.
 */
public class InterstitialCadenceTest {

    /**
     * The answer key, and deliberately a literal.
     *
     * <p>An earlier version of this test derived its expectations from
     * {@code GameEngine.INTERSTITIAL_EVERY}. The fault matrix (matrix/, row F3) then changed
     * that constant and this test kept passing, because the expectation moved with the fault —
     * it was checking that the predicate agrees with the constant, which is always true. A
     * literal sequence cannot track the code: changing the cadence now has to come here and be
     * a visible, deliberate act.
     */
    private static final boolean[] CUE_AFTER_RUN = {false, false, true};

    /** Parks the player and lets an obstacle land, so a run ends without needing to play well. */
    private static long endOneRun(GameEngine e, long from) {
        for (int f = 1; f <= 4000; f++) {
            long now = from + f * 16L;
            e.update(now, 16f);
            if (e.isGameOver()) return now;
        }
        throw new AssertionError("the run never ended within 4000 frames");
    }

    private static GameEngine fresh() {
        GameEngine e = new GameEngine(new Rng(7L));
        e.setBounds(1080, 2200);
        e.start(0L);
        return e;
    }

    @Test
    public void theCueFollowsTheRecordedCadenceExactly() {
        GameEngine e = fresh();
        assertFalse(e.consumeInterstitialCue(), "cued before any run had ended");

        long t = 0;
        for (int run = 1; run <= CUE_AFTER_RUN.length; run++) {
            t = endOneRun(e, t) + 1_000;
            assertEquals(CUE_AFTER_RUN[run - 1], e.consumeInterstitialCue(),
                    "cue after " + run + " completed runs disagrees with the recorded cadence");
            if (run < CUE_AFTER_RUN.length) e.start(t);
        }
    }

    @Test
    public void theCueIsSpentByReadingIt() {
        GameEngine e = fresh();
        long t = 0;
        for (int run = 1; run <= CUE_AFTER_RUN.length; run++) {
            t = endOneRun(e, t) + 1_000;
            if (run < CUE_AFTER_RUN.length) e.start(t);
        }

        assertTrue(e.consumeInterstitialCue(),
                "precondition: the last recorded run should cue");
        assertFalse(e.consumeInterstitialCue(),
                "the cue survived being read — a caller that checks twice shows two ads");
    }

    /**
     * The one that pays for itself: a continue happens after {@code endRun}, so without the
     * correction in {@link GameEngine#useContinue} a player who watched a rewarded ad is moved a
     * step closer to an interstitial for the same death.
     *
     * <p>The continue is taken on the <em>first</em> run: earning one needs
     * {@link GameEngine#MIN_CONTINUE_SCORE}, and the obstacle stream has to be fresh for a
     * dodging player to get there.
     */
    @Test
    public void takingAContinueDoesNotCountAsACompletedRun() {
        GameEngine e = fresh();

        long died = playWellUntilDead(e, 0);
        assertTrue(e.canUseContinue(),
                "precondition: the first run must earn a continue, scored " + e.score()
                        + " and needs " + GameEngine.MIN_CONTINUE_SCORE);

        e.useContinue(died + 500);          // one death, paid for with a rewarded ad
        long t = endOneRun(e, died + 1_000);  // the resumed run then ends for real: that is run 1

        // Complete runs up to one before the recorded cue. The honest total reaches the
        // cadence only if the continued death was NOT double counted. Assert the run before
        // the cue is quiet.
        for (int more = 1; more < CUE_AFTER_RUN.length - 1; more++) {
            e.start(t + 1_000);
            t = endOneRun(e, t + 1_000);
        }
        assertFalse(e.consumeInterstitialCue(),
                "cued one run early — the continued death was counted twice, so watching a "
                        + "rewarded ad brought the interstitial forward");

        e.start(t + 1_000);
        endOneRun(e, t + 1_000);
        assertTrue(e.consumeInterstitialCue(),
                "no cue after " + CUE_AFTER_RUN.length + " honest runs");
    }

    /** Steers away from the nearest obstacle above the player, which is enough to score. */
    private static long playWellUntilDead(GameEngine e, long from) {
        for (int f = 1; f <= 20_000; f++) {
            GameEngine.Obstacle threat = null;
            float nearest = Float.MAX_VALUE;
            for (GameEngine.Obstacle o : e.obstacles()) {
                float above = e.playerY() - o.y;
                if (above > 0 && above < nearest) {
                    nearest = above;
                    threat = o;
                }
            }
            if (threat != null) {
                e.setTargetX(threat.x > 540f ? 150f : 930f);
            }
            long now = from + f * 16L;
            e.update(now, 16f);
            if (e.isGameOver()) return now;
        }
        throw new AssertionError("the run never ended within 20000 frames");
    }
}
