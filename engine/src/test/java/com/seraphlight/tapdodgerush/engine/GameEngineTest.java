package com.seraphlight.tapdodgerush.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The rules, checked without an emulator. */
public class GameEngineTest {

    private static final int W = 1080;
    private static final int H = 1920;

    private static GameEngine engine(long seed) {
        GameEngine e = new GameEngine(new Rng(seed));
        e.setBounds(W, H);
        e.start(0L);
        return e;
    }

    /** Advance the world in reference-sized frames from a fixed clock. */
    private static void run(GameEngine e, int frames) {
        for (int i = 1; i <= frames; i++) {
            e.update((long) (i * GameEngine.REFERENCE_FRAME_MS), GameEngine.REFERENCE_FRAME_MS);
        }
    }

    @Test
    public void ranksAreOrderedAndBoundedByScore() {
        assertEquals(GameEngine.rankFor(0), "Rookie");
        assertEquals(GameEngine.rankFor(9), "Rookie");
        assertEquals(GameEngine.rankFor(10), "Sharp");
        assertEquals(GameEngine.rankFor(25), "Locked In");
        assertEquals(GameEngine.rankFor(50), "Untouchable");
        assertEquals(GameEngine.rankFor(Integer.MAX_VALUE), "Untouchable");
    }

    @Test
    public void aFreshRunStartsCentredAndScoreless() {
        GameEngine e = engine(1);
        assertEquals(W / 2f, e.playerX(), 0.001f);
        assertEquals(0, e.score());
        assertTrue(e.isRunning());
        assertFalse(e.isGameOver());
    }

    @Test
    public void playerNeverLeavesTheBoard() {
        GameEngine e = engine(2);
        e.setTargetX(-99999f);
        run(e, 200);
        assertTrue(e.playerX() >= GameEngine.PLAYER_SIZE - 0.001f, "left edge: " + e.playerX());

        e.setTargetX(99999f);
        run(e, 200);
        assertTrue(e.playerX() <= W - GameEngine.PLAYER_SIZE + 0.001f, "right edge: " + e.playerX());
    }

    /**
     * The bug this extraction exists to fix. The original moved the world a fixed amount per
     * frame, so a display running at twice the rate ran the game at twice the speed. Stepping
     * the same wall-clock span in halves must land in the same place.
     */
    @Test
    public void worldAdvancesByElapsedTimeNotByFrameCount() {
        GameEngine sixty = engine(7);
        GameEngine oneTwenty = engine(7);

        sixty.setTargetX(900f);
        oneTwenty.setTargetX(900f);

        for (int i = 1; i <= 60; i++) {
            sixty.update(i * 16L, 16f);
        }
        for (int i = 1; i <= 120; i++) {
            oneTwenty.update(i * 8L, 8f);
        }

        assertEquals(sixty.playerX(), oneTwenty.playerX(), 1.0f,
                "player drifted apart across frame rates");

        // The obstacles matter more than the player here: they are what the original
        // advanced by a flat amount per frame, so a 120Hz display ran the game at double
        // speed. Comparing only the player misses that entirely.
        assertEquals(sixty.obstacles().size(), oneTwenty.obstacles().size(),
                "different number of obstacles alive across frame rates");

        // Spawn checks are wall-clock and discrete, so a finer tick can catch the spawn
        // interval up to one tick sooner and that obstacle is correspondingly further down
        // the screen. That residue is bounded by one reference frame of fall — about 30px
        // at the top speed — and is quantisation, not a scaling error. A drift larger than
        // this would mean the world really is advancing per-frame again.
        float oneFrameOfFall = 30f;
        for (int i = 0; i < sixty.obstacles().size(); i++) {
            assertEquals(sixty.obstacles().get(i).y, oneTwenty.obstacles().get(i).y, oneFrameOfFall,
                    "obstacle " + i + " fell a different distance across frame rates");
            assertEquals(sixty.obstacles().get(i).x, oneTwenty.obstacles().get(i).x, 2.0f,
                    "obstacle " + i + " drifted differently across frame rates");
        }
    }

    /**
     * The clamp in update() is a second line of defence — setTargetX clamps too, so removing
     * either alone leaves the invariant holding. This drives the player directly to prove
     * the update-side clamp is load-bearing on its own.
     */
    @Test
    public void updateClampsThePlayerEvenWhenTheTargetIsInsideTheBoard() {
        GameEngine e = engine(11);
        e.setTargetX(W / 2f);
        // A legal target, but a huge dt: the easing must not overshoot the wall.
        e.update(16L, 100_000f);
        assertTrue(e.playerX() >= GameEngine.PLAYER_SIZE - 0.001f, "left: " + e.playerX());
        assertTrue(e.playerX() <= W - GameEngine.PLAYER_SIZE + 0.001f, "right: " + e.playerX());
    }

    @Test
    public void aContinueIsOfferedOnlyOnceAndOnlyAfterMinimumScore() {
        GameEngine e = engine(3);
        assertFalse(e.canUseContinue(), "not offered mid-run");

        // Drive to game over without scoring: park the player and let one obstacle land.
        GameEngine dead = engine(3);
        int guard = 0;
        while (!dead.isGameOver() && guard++ < 100_000) {
            dead.update(guard * 16L, 16f);
            if (!dead.obstacles().isEmpty()) {
                dead.setTargetX(dead.obstacles().get(0).x);
            }
        }
        assertTrue(dead.isGameOver(), "expected a collision");
        assertTrue(dead.score() < GameEngine.MIN_CONTINUE_SCORE, "score below the threshold");
        assertFalse(dead.canUseContinue(), "a continue must not be offered below the threshold");
    }

    @Test
    public void gameOverIsTerminalUntilRestarted() {
        GameEngine e = engine(4);
        int guard = 0;
        while (!e.isGameOver() && guard++ < 100_000) {
            e.update(guard * 16L, 16f);
            if (!e.obstacles().isEmpty()) {
                e.setTargetX(e.obstacles().get(0).x);
            }
        }
        assertTrue(e.isGameOver());

        int scoreAtDeath = e.score();
        run(e, 300);
        assertEquals(scoreAtDeath, e.score(), "the world kept running after game over");
        assertFalse(e.isRunning());
    }

    @Test
    public void theSameSeedProducesTheSameRun() {
        GameEngine a = engine(99);
        GameEngine b = engine(99);
        run(a, 400);
        run(b, 400);
        assertEquals(a.score(), b.score());
        assertEquals(a.obstacles().size(), b.obstacles().size());
        for (int i = 0; i < a.obstacles().size(); i++) {
            assertEquals(a.obstacles().get(i).x, b.obstacles().get(i).x, 0.0001f);
            assertEquals(a.obstacles().get(i).y, b.obstacles().get(i).y, 0.0001f);
        }
    }

    @Test
    public void differentSeedsDiverge() {
        GameEngine a = engine(1);
        GameEngine b = engine(2);
        run(a, 400);
        run(b, 400);
        assertNotEquals(a.obstacles().get(0).x, b.obstacles().get(0).x, "two seeds produced an identical world");
    }

    @Test
    public void bestScoreOnlyRises() {
        GameEngine e = engine(5);
        e.setBestScore(40);
        int guard = 0;
        while (!e.isGameOver() && guard++ < 100_000) {
            e.update(guard * 16L, 16f);
            if (!e.obstacles().isEmpty()) {
                e.setTargetX(e.obstacles().get(0).x);
            }
        }
        assertEquals(40, e.bestScore(), "a worse run lowered the best score");
        assertFalse(e.lastRunWasNewBest());
    }

    @Test
    public void updateBeforeBoundsAreKnownIsASafeNoOp() {
        GameEngine e = new GameEngine(new Rng(6));
        e.start(0L);
        e.update(16L, 16f); // width and height are still zero
        assertEquals(0, e.score());
    }
}
