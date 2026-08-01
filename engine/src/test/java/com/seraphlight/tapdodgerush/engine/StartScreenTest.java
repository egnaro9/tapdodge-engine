package com.seraphlight.tapdodgerush.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player has to be somewhere sensible before the first tap.
 *
 * <p>It was not. {@code playerX} and {@code playerY} were assigned only inside {@code start()},
 * so on the start screen they were both zero and every renderer drew the block in the top-left
 * corner — on the phone as much as in the browser. Nothing failed, because no test looked at
 * the engine before a run began.
 */
public class StartScreenTest {

    private static GameEngine fresh() {
        return new GameEngine(new Rng(1L));
    }

    @Test
    public void thePlayerIsParkedAtItsStartPositionBeforeTheFirstRun() {
        GameEngine e = fresh();
        e.setBounds(1080, 1920);

        assertEquals(540f, e.playerX(), 0.001f, "player should be centred on the start screen");
        assertEquals(1920 - GameEngine.PLAYER_BOTTOM_MARGIN, e.playerY(), 0.001f,
                "player should sit at its running height on the start screen");
        assertTrue(e.playerY() > 0, "player must not be drawn at the origin");
    }

    @Test
    public void resizingMidRunDoesNotTeleportThePlayerBackToTheMiddle() {
        GameEngine e = fresh();
        e.setBounds(1080, 1920);
        e.start(0L);
        e.setTargetX(900f);
        for (int f = 1; f <= 40; f++) {
            e.update(f * 16L, 16f);
        }

        float moved = e.playerX();
        assertTrue(moved > 600f, "precondition: the player should have moved right, was " + moved);

        // Both renderers call setBounds every frame, so this is the realistic case, not a corner one.
        e.setBounds(1080, 1920);

        assertEquals(moved, e.playerX(), 0.001f,
                "setBounds must not reposition the player once a run is under way");
    }

    @Test
    public void theStartPositionSurvivesARestart() {
        GameEngine e = fresh();
        e.setBounds(1080, 1920);
        e.start(0L);
        e.setTargetX(200f);
        for (int f = 1; f <= 30; f++) {
            e.update(f * 16L, 16f);
        }

        e.start(1000L);
        assertEquals(540f, e.playerX(), 0.001f, "a restart should re-centre the player");
    }
}
