package com.seraphlight.tapdodgerush.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The restart rules, which lived in MainActivity where nothing could test them.
 *
 * <p>The :app module's entire unit suite was {@code assertEquals(4, 2 + 2)}, the untouched
 * Android Studio template. It imports no project symbol, so by construction no edit to any of
 * the 770 lines in :app could make it fail, and CI's unit-test gate was that tautology. Both
 * rules below were confirmed missing by planting the mutation and watching the suite stay green.
 */
public class RestartFlowTest {

    /** Records whether the cue was asked for, which is what the short circuit protects. */
    private static final class SpyCue implements RestartFlow.CueSource {
        private final boolean available;
        private int asked;

        SpyCue(boolean available) {
            this.available = available;
        }

        @Override
        public boolean consumeInterstitialCue() {
            asked++;
            return available;
        }
    }

    /** Parks the player and lets an obstacle land, so a run ends without playing well. */
    private static long endOneRun(GameEngine e, long from) {
        for (int f = 1; f <= 4000; f++) {
            long now = from + f * 16L;
            e.update(now, 16f);
            if (e.isGameOver()) return now;
        }
        throw new AssertionError("the run never ended within 4000 frames");
    }

    @Test
    public void aLoadedAdAndAnAvailableCueShowsTheInterstitial() {
        SpyCue cue = new SpyCue(true);
        assertEquals(RestartFlow.Action.SHOW_INTERSTITIAL, RestartFlow.onRestart(true, cue));
        assertEquals(1, cue.asked, "the cue must be spent exactly once");
    }

    @Test
    public void aLoadedAdWithNoCueDueStartsTheNextRun() {
        SpyCue cue = new SpyCue(false);
        assertEquals(RestartFlow.Action.BEGIN_NEXT_RUN, RestartFlow.onRestart(true, cue));
        assertEquals(1, cue.asked);
    }

    @Test
    public void withNoAdLoadedTheCueIsNotEvenConsulted() {
        // The whole reason the condition is `ad != null && consumeInterstitialCue()` and not the
        // other way round. consumeInterstitialCue RESETS the counter when it returns true, so
        // asking with no ad in hand burns a cue on an ad that never shows and costs the player a
        // full interstitial cycle. Swapping the operands reads identically and is wrong.
        SpyCue cue = new SpyCue(true);
        assertEquals(RestartFlow.Action.BEGIN_NEXT_RUN, RestartFlow.onRestart(false, cue));
        assertEquals(0, cue.asked, "the cue must be untouched when no ad is loaded");
    }

    @Test
    public void theCueSurvivesARestartThatFoundNoAd() {
        // Against the real engine rather than the spy: earn a cue, restart with no ad loaded,
        // and the cue must still be there for the next restart that does have one.
        GameEngine e = new GameEngine(new Rng(7L));
        e.setBounds(1080, 2200);
        e.start(0L);
        long t = 0;
        for (int run = 1; run < GameEngine.INTERSTITIAL_EVERY; run++) {
            t = endOneRun(e, t) + 1_000;
            e.start(t);
        }
        endOneRun(e, t);

        RestartFlow.CueSource cue = e::consumeInterstitialCue;
        assertEquals(RestartFlow.Action.BEGIN_NEXT_RUN, RestartFlow.onRestart(false, cue));
        assertEquals(RestartFlow.Action.SHOW_INTERSTITIAL, RestartFlow.onRestart(true, cue),
                "a restart that showed nothing burned the cue");
    }

    @Test
    public void everyWayAnInterstitialCanEndStartsTheNextRun() {
        // Dismissal and failure-to-show both route here. A Google Mobile Ads failure is outside
        // our control and must not cost the player their restart.
        assertEquals(RestartFlow.Action.BEGIN_NEXT_RUN, RestartFlow.afterInterstitial());
    }

    @Test
    public void noRestartPathEverStrandsThePlayer() {
        // The invariant across all four paths: the next run always begins, either immediately or
        // when the interstitial ends. Nothing leaves the player on the game over screen.
        for (boolean adReady : new boolean[] {true, false}) {
            for (boolean cueDue : new boolean[] {true, false}) {
                RestartFlow.Action a = RestartFlow.onRestart(adReady, new SpyCue(cueDue));
                boolean reaches = a == RestartFlow.Action.BEGIN_NEXT_RUN
                        || RestartFlow.afterInterstitial() == RestartFlow.Action.BEGIN_NEXT_RUN;
                assertTrue(reaches, "adReady=" + adReady + " cueDue=" + cueDue + " stranded the player");
            }
        }
        assertEquals(2, RestartFlow.Action.values().length,
                "a new action needs its own path to the next run before it is added here");
    }
}
