// Exposes the engine to JavaScript, compiled by TeaVM.
//
// The browser build runs the same compiled rules as the Android app — the renderer
// differs, the game does not. Only primitives and one JSON string cross the boundary,
// which are the types @JSExport handles natively, so the engine needs no knowledge
// that JavaScript exists.
//
// No continue is exposed. The Play build buys one with a rewarded ad; there is no ad
// here, and handing out free continues would make the score mean something different
// from the score in the shipped game.
package com.seraphlight.tapdodgerush.web;

import com.seraphlight.tapdodgerush.engine.GameEngine;
import com.seraphlight.tapdodgerush.engine.Rng;

import org.teavm.jso.JSExport;

public final class TapDodgeJs {

    private static GameEngine engine;
    private static int lastScore = -1;
    private static boolean deathPending;

    private TapDodgeJs() {}

    /** @param seed a fixed seed reproduces a run exactly; pass 0 for a random one. */
    @JSExport
    public static void create(double seed, int width, int height) {
        engine = new GameEngine(new Rng(seed == 0 ? (long) (Math.random() * 1e9) : (long) seed));
        engine.setBounds(width, height);
        engine.setListener(new GameEngine.Listener() {
            @Override public void onScore(int s) { lastScore = s; }
            @Override public void onDeathStarted() { deathPending = true; }
        });
    }

    @JSExport
    public static void setBounds(int width, int height) {
        engine.setBounds(width, height);
    }

    @JSExport
    public static void setBestScore(int best) {
        engine.setBestScore(best);
    }

    @JSExport
    public static void start(double nowMillis) {
        engine.start((long) nowMillis);
    }

    @JSExport
    public static void update(double nowMillis, double elapsedMillis) {
        engine.update((long) nowMillis, (float) elapsedMillis);
    }

    @JSExport
    public static void setTargetX(double x) {
        engine.setTargetX((float) x);
    }

    /** True once per death, so the renderer can fire a sound without polling state. */
    @JSExport
    public static boolean consumeDeath() {
        boolean d = deathPending;
        deathPending = false;
        return d;
    }

    /** True once per point scored. */
    @JSExport
    public static int consumeScored() {
        int s = lastScore;
        lastScore = -1;
        return s;
    }

    /**
     * The whole visible world as JSON.
     *
     * <p>Hand-built rather than serialized: TeaVM has no reflection, and one string a
     * frame is cheaper than exporting an accessor per field and crossing the boundary
     * thirty times.
     */
    @JSExport
    public static String state() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"px\":").append(engine.playerX())
          .append(",\"py\":").append(engine.playerY())
          .append(",\"size\":").append(GameEngine.PLAYER_SIZE)
          .append(",\"score\":").append(engine.score())
          .append(",\"best\":").append(engine.bestScore())
          .append(",\"streak\":").append(engine.streak())
          .append(",\"running\":").append(engine.isRunning())
          .append(",\"over\":").append(engine.isGameOver())
          .append(",\"started\":").append(engine.hasStarted())
          .append(",\"hitFlash\":").append(engine.hitFlashFrames())
          .append(",\"scorePop\":").append(engine.scorePopFrames())
          .append(",\"nearMiss\":").append(engine.nearMissPopupFrames())
          .append(",\"nearMissX\":").append(engine.nearMissPopupX())
          .append(",\"nearMissY\":").append(engine.nearMissPopupY())
          .append(",\"rankUp\":").append(engine.rankUpPopupFrames())
          .append(",\"rankUpTitle\":\"").append(engine.rankUpTitle()).append('"')
          .append(",\"newBest\":").append(engine.newBestPopupFrames())
          .append(",\"rank\":\"").append(GameEngine.rankFor(engine.score())).append('"')
          .append(",\"obstacles\":[");
        boolean first = true;
        for (GameEngine.Obstacle o : engine.obstacles()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(o.x).append(',').append(o.y).append(',').append(o.size).append(']');
        }
        return sb.append("]}").toString();
    }

    /** Exposed so the page can show the same ladder the app does. */
    @JSExport
    public static String rankFor(int score) {
        return GameEngine.rankFor(score);
    }
}
