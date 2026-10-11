package com.seraphlight.tapdodgerush.engine;

/**
 * What a restart tap does, decided without Android in the room.
 *
 * <p>This lived inline in {@code MainActivity} as two conditions inside a click listener and an
 * ad callback, which made it untestable: the only unit test the {@code :app} module had was the
 * Android Studio template, {@code assertEquals(4, 2 + 2)}, so no edit to any of those 770 lines
 * could make a test fail. The rules below are small but both are load-bearing and both were
 * unasserted.
 *
 * <p><b>The cue is only spent when an interstitial is actually ready.</b> The original condition
 * is {@code ad != null && consumeInterstitialCue()}, and the short circuit is the point:
 * {@link GameEngine#consumeInterstitialCue()} RESETS the counter when it returns true, so asking
 * it with no ad loaded would burn a cue on an ad that never shows and cost the player a whole
 * interstitial cycle. Swapping the operands reads identically and is wrong.
 *
 * <p><b>The next run starts on every path.</b> Shown and dismissed, failed to show, no cue, no
 * ad: all four reach {@code beginNextRun}. The failure path is the one that matters, because a
 * Google Mobile Ads failure is outside our control and must not cost the player their restart.
 */
public final class RestartFlow {

    /** What the caller should do now. */
    public enum Action {
        /** Show the loaded interstitial, then call {@link #afterInterstitial()} when it ends. */
        SHOW_INTERSTITIAL,
        /** Start the next run immediately. */
        BEGIN_NEXT_RUN,
    }

    /** The cue side of the engine, so this class needs no engine instance to be tested. */
    public interface CueSource {
        /** Spends the cue and reports whether one was available. */
        boolean consumeInterstitialCue();
    }

    private RestartFlow() {
    }

    /**
     * Decide what a restart tap does.
     *
     * @param interstitialReady whether a loaded interstitial is in hand RIGHT NOW
     * @param cue               consulted ONLY when an interstitial is ready, because asking
     *                          spends the cue
     */
    public static Action onRestart(boolean interstitialReady, CueSource cue) {
        if (interstitialReady && cue.consumeInterstitialCue()) {
            return Action.SHOW_INTERSTITIAL;
        }
        return Action.BEGIN_NEXT_RUN;
    }

    /**
     * What happens once the interstitial ends, however it ended.
     *
     * <p>Both terminal callbacks route here, so a dismissal and a failure to show cannot drift
     * apart. There is deliberately no parameter: nothing about how the ad ended may change
     * whether the player gets their run.
     */
    public static Action afterInterstitial() {
        return Action.BEGIN_NEXT_RUN;
    }
}
