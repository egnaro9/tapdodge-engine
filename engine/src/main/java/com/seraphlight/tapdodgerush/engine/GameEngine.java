package com.seraphlight.tapdodgerush.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The rules of Tap Dodge Rush, with no rendering and no platform in them.
 *
 * <p>Extracted from {@code GameView} so the game can be tested without an emulator and,
 * later, compiled to the browser the way {@code match3-engine} is. Nothing here imports
 * Android, and the clock and the RNG are both supplied by the caller — which is what makes
 * a run reproducible: the same seed and the same sequence of {@link #update} calls always
 * produce the same game.
 *
 * <p><b>One behaviour deliberately changed in the extraction.</b> The original advanced the
 * world by a fixed amount per frame while spawning against the wall clock, which held
 * together only because the Android loop was pinned to {@code postDelayed(16)}. On a display
 * running faster than 60Hz the game would run proportionally fast, and on a device dropping
 * frames obstacles moved slower while continuing to spawn on schedule. {@link #update} now
 * takes elapsed milliseconds and scales movement by it, so behaviour is the same at any
 * frame rate. At a steady 60fps the numbers are identical to the original.
 */
public final class GameEngine {

    /** The reference tick the original constants were tuned against. */
    public static final float REFERENCE_FRAME_MS = 16f;

    public static final float PLAYER_SIZE = 80f;
    public static final float PLAYER_BOTTOM_MARGIN = 220f;
    /** Fraction of the frame's remaining distance the player covers per reference frame. */
    public static final float PLAYER_EASING = 0.22f;
    /** A run must reach this score before a continue may be offered. */
    public static final int MIN_CONTINUE_SCORE = 10;

    /** Callbacks for things the platform owns: sound, haptics, the game-over screen. */
    public interface Listener {
        default void onScore(int newScore) {}
        default void onNearMiss(float x, float y) {}
        default void onRankUp(String rank) {}
        default void onDeathStarted() {}
        default void onGameOver(int score, boolean newBest) {}
    }

    private static final Listener NO_OP = new Listener() {};

    private final Rng random;
    private Listener listener = NO_OP;

    private final List<Obstacle> obstacles = new ArrayList<>();

    private float playerX, playerY, targetX;
    private int width, height;

    private boolean gameRunning, gameOver, hasStarted, dying;
    private boolean usedContinueThisRun, lastRunWasNewBest;

    private int score, bestScore, streakCount;
    private String lastRunRankTitle = "";
    private String rankUpTitle = "";

    // Frame-counted presentation timers. The renderer reads these; the rules do not
    // branch on them, except deathDelay which paces the death animation.
    private float hitFlashFrames, deathDelayFrames, scorePopFrames;
    private float nearMissPopupFrames, rankUpPopupFrames, newBestPopupFrames;
    private float nearMissPopupX, nearMissPopupY;

    private long gameStartTime, lastSpawnTime;

    public GameEngine(Rng random) {
        this.random = random;
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? NO_OP : listener;
    }

    public void setBounds(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** Restores the persisted best score. The platform owns where that came from. */
    public void setBestScore(int best) {
        this.bestScore = best;
    }

    public void start(long nowMillis) {
        obstacles.clear();
        score = 0;
        streakCount = 0;
        gameOver = false;
        hasStarted = true;
        gameRunning = true;
        dying = false;
        usedContinueThisRun = false;
        lastRunWasNewBest = false;

        hitFlashFrames = deathDelayFrames = scorePopFrames = 0;
        nearMissPopupFrames = rankUpPopupFrames = newBestPopupFrames = 0;
        rankUpTitle = "";

        centrePlayer();
        gameStartTime = nowMillis;
        lastSpawnTime = nowMillis;
    }

    /**
     * Advance the world.
     *
     * @param nowMillis     the caller's clock, used only for spawn scheduling and difficulty
     * @param elapsedMillis time since the previous update; movement scales by this
     */
    public void update(long nowMillis, float elapsedMillis) {
        if (!gameRunning || width == 0 || height == 0) {
            return;
        }
        float frames = elapsedMillis / REFERENCE_FRAME_MS;

        if (dying) {
            hitFlashFrames = Math.max(0, hitFlashFrames - frames);
            deathDelayFrames -= frames;
            if (deathDelayFrames > 0) {
                return;
            }
            endRun();
            return;
        }

        // Easing is per-reference-frame, so compounding it over `frames` keeps the same
        // curve at any refresh rate rather than moving `frames` times as far.
        playerX += (targetX - playerX) * (1f - (float) Math.pow(1f - PLAYER_EASING, frames));
        playerX = Math.max(PLAYER_SIZE, Math.min(width - PLAYER_SIZE, playerX));

        float elapsedSeconds = (nowMillis - gameStartTime) / 1000f;
        float obstacleSpeed = 16f + Math.min(elapsedSeconds * 0.35f, 14f);
        long spawnInterval = Math.max(160, 520 - (long) (elapsedSeconds * 22f));

        if (nowMillis - lastSpawnTime >= spawnInterval) {
            obstacles.add(new Obstacle(
                    60f + random.nextInt(Math.max(1, width - 120)),
                    -60f,
                    -2.5f + random.nextFloat() * 5f,
                    28f + random.nextFloat() * 26f));
            lastSpawnTime = nowMillis;
        }

        Iterator<Obstacle> it = obstacles.iterator();
        while (it.hasNext()) {
            Obstacle o = it.next();
            o.x += o.driftX * frames;
            o.y += obstacleSpeed * frames;

            if (o.y - o.size > height) {
                it.remove();
                scorePoint();
                continue;
            }

            float hitX = (PLAYER_SIZE + o.size) * 0.78f;
            float hitY = (PLAYER_SIZE + o.size) * 0.78f;
            float dx = Math.abs(o.x - playerX);
            float dy = Math.abs(o.y - playerY);

            if (!o.nearMissCounted && dx < hitX * 1.30f && dy < hitY * 1.30f
                    && !(dx < hitX && dy < hitY)) {
                o.nearMissCounted = true;
                nearMissPopupFrames = 18;
                nearMissPopupX = o.x;
                nearMissPopupY = o.y;
                listener.onNearMiss(o.x, o.y);
            }

            if (dx < hitX && dy < hitY) {
                triggerDeath();
                return;
            }
        }

        scorePopFrames = Math.max(0, scorePopFrames - frames);
        nearMissPopupFrames = Math.max(0, nearMissPopupFrames - frames);
        rankUpPopupFrames = Math.max(0, rankUpPopupFrames - frames);
        newBestPopupFrames = Math.max(0, newBestPopupFrames - frames);
    }

    private void scorePoint() {
        String previousRank = rankFor(score);
        score++;
        streakCount++;
        scorePopFrames = 7;
        listener.onScore(score);

        String newRank = rankFor(score);
        if (!previousRank.equals(newRank)) {
            rankUpTitle = newRank;
            rankUpPopupFrames = 34;
            listener.onRankUp(newRank);
        }
    }

    private void triggerDeath() {
        if (dying || gameOver) {
            return;
        }
        dying = true;
        hitFlashFrames = 10;
        deathDelayFrames = 18;
        listener.onDeathStarted();
    }

    private void endRun() {
        gameRunning = false;
        gameOver = true;
        hasStarted = true;
        dying = false;
        lastRunRankTitle = rankFor(score);
        lastRunWasNewBest = score > bestScore;
        if (lastRunWasNewBest) {
            bestScore = score;
            newBestPopupFrames = 40;
        }
        listener.onGameOver(score, lastRunWasNewBest);
    }

    /** A continue is offered once per run, and only after the run was worth continuing. */
    public boolean canUseContinue() {
        return gameOver && !usedContinueThisRun && score >= MIN_CONTINUE_SCORE;
    }

    public void useContinue(long nowMillis) {
        if (!canUseContinue()) {
            return;
        }
        usedContinueThisRun = true;
        gameOver = false;
        gameRunning = true;
        dying = false;
        nearMissPopupFrames = rankUpPopupFrames = newBestPopupFrames = 0;
        obstacles.clear();
        centrePlayer();
        lastSpawnTime = nowMillis;
    }

    public void setTargetX(float x) {
        this.targetX = Math.max(PLAYER_SIZE, Math.min(Math.max(PLAYER_SIZE, width - PLAYER_SIZE), x));
    }

    private void centrePlayer() {
        playerX = width / 2f;
        targetX = playerX;
        playerY = height - PLAYER_BOTTOM_MARGIN;
    }

    public static String rankFor(int score) {
        if (score >= 50) return "Untouchable";
        if (score >= 25) return "Locked In";
        if (score >= 10) return "Sharp";
        return "Rookie";
    }

    // --- read-only state, for renderers and tests -------------------------------------

    public float playerX() { return playerX; }
    public float playerY() { return playerY; }
    public List<Obstacle> obstacles() { return obstacles; }
    public int score() { return score; }
    public int bestScore() { return bestScore; }
    public int streak() { return streakCount; }
    public boolean isRunning() { return gameRunning; }
    public boolean isGameOver() { return gameOver; }
    public boolean hasStarted() { return hasStarted; }
    public boolean isDying() { return dying; }
    public String lastRunRankTitle() { return lastRunRankTitle; }
    public boolean lastRunWasNewBest() { return lastRunWasNewBest; }
    public String rankUpTitle() { return rankUpTitle; }
    public float hitFlashFrames() { return hitFlashFrames; }
    public float scorePopFrames() { return scorePopFrames; }
    public float nearMissPopupFrames() { return nearMissPopupFrames; }
    public float rankUpPopupFrames() { return rankUpPopupFrames; }
    public float newBestPopupFrames() { return newBestPopupFrames; }
    public float nearMissPopupX() { return nearMissPopupX; }
    public float nearMissPopupY() { return nearMissPopupY; }

    /** One falling obstacle. Public fields: this is a struct the renderer walks every frame. */
    public static final class Obstacle {
        public float x, y, driftX, size;
        public boolean nearMissCounted;

        Obstacle(float x, float y, float driftX, float size) {
            this.x = x;
            this.y = y;
            this.driftX = driftX;
            this.size = size;
        }
    }
}
