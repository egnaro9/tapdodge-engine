package com.seraphlight.tapdodgerush.engine;

import org.junit.jupiter.api.Test;

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
    public void aCueArrivesOnTheThirdCompletedRunAndNotBefore() {
        GameEngine e = fresh();
        assertFalse(e.consumeInterstitialCue(), "cued before any run had ended");

        long t = 0;
        for (int run = 1; run < GameEngine.INTERSTITIAL_EVERY; run++) {
            t = endOneRun(e, t) + 1_000;
            assertFalse(e.consumeInterstitialCue(),
                    "cued after only " + run + " completed runs; the interval is "
                            + GameEngine.INTERSTITIAL_EVERY);
            e.start(t);
        }

        endOneRun(e, t);
        assertTrue(e.consumeInterstitialCue(),
                "no cue after " + GameEngine.INTERSTITIAL_EVERY + " completed runs");
    }

    @Test
    public void theCueIsSpentByReadingIt() {
        GameEngine e = fresh();
        long t = 0;
        for (int run = 1; run <= GameEngine.INTERSTITIAL_EVERY; run++) {
            t = endOneRun(e, t) + 1_000;
            if (run < GameEngine.INTERSTITIAL_EVERY) e.start(t);
        }

        assertTrue(e.consumeInterstitialCue(), "precondition: the third run should cue");
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

        // Two more complete runs brings the honest total to INTERSTITIAL_EVERY - 1 + 1 = 3 only
        // if the continued death was NOT double counted. Assert the run before the cue is quiet.
        for (int more = 1; more < GameEngine.INTERSTITIAL_EVERY - 1; more++) {
            e.start(t + 1_000);
            t = endOneRun(e, t + 1_000);
        }
        assertFalse(e.consumeInterstitialCue(),
                "cued one run early — the continued death was counted twice, so watching a "
                        + "rewarded ad brought the interstitial forward");

        e.start(t + 1_000);
        endOneRun(e, t + 1_000);
        assertTrue(e.consumeInterstitialCue(),
                "no cue after " + GameEngine.INTERSTITIAL_EVERY + " honest runs");
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
