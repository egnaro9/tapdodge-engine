package com.seraphlight.tapdodgerush.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The play area can change height WHILE a run is under way.
 *
 * <p>Observed on a Samsung SM-S911U, 1.6 + the 120px margin, 2026-08-06: after an
 * interstitial dismissed, the block was drawn at rows 1992..2045: a 160px block with
 * only 54px visible, its bottom sheared off at the play-area floor (2046) and sitting
 * flush against the banner. Measured from the operator's screenshot.
 *
 * <p>Cause: {@code playerY} is assigned in exactly one place, {@code centrePlayer()}, and
 * and {@code setBounds} only calls it while {@code !hasStarted}. The guard exists for a
 * real reason (both renderers call setBounds every frame, so an unguarded re-centre would
 * yank the player every tick). But it does not distinguish "same bounds again" from
 * "the view genuinely got shorter", so a height captured during an intermediate layout
 * pass is latched for the rest of the session.
 *
 * <p>The window really does re-lay-out in two passes here: the inset listener sets the
 * nav-bar margin and the banner reserve, and a height sampled between them is
 * {@code 2340 - 148 = 2192}. With a 120 margin that puts playerY at 2072 and the block
 * at 1992..2152, exactly what was photographed.
 */
class BoundsChangeTest {

    /** A shorter play area must move the player up with it, not leave it below the floor. */
    @Test
    void playerFollowsAShrinkingPlayArea() {
        GameEngine e = new GameEngine(new Rng(1));
        e.setBounds(1080, 2192);        // intermediate pass: nav inset only
        e.start(0L);
        assertEquals(2192 - GameEngine.PLAYER_BOTTOM_MARGIN, e.playerY(), 0.001f,
                "sanity: the player starts against the bounds it was given");

        e.setBounds(1080, 2046);        // settled pass: nav inset + banner reserve

        assertEquals(2046 - GameEngine.PLAYER_BOTTOM_MARGIN, e.playerY(), 0.001f,
                "a genuine height change must reposition the player; otherwise playerY is "
                        + "latched from a stale layout pass and the block is clipped");
    }

    /** The block must never extend past the floor, whatever order the passes arrive in. */
    @Test
    void blockIsNeverClippedByThePlayAreaFloor() {
        GameEngine e = new GameEngine(new Rng(1));
        e.setBounds(1080, 2192);
        e.start(0L);
        e.setBounds(1080, 2046);

        float blockBottom = e.playerY() + GameEngine.PLAYER_SIZE;
        assertTrue(blockBottom <= 2046,
                "block bottom " + blockBottom + " is below the play-area floor 2046, it would "
                        + "be sheared off and sit flush against the banner");
    }

    /** The original guard still has to hold: identical bounds must not move a live player. */
    @Test
    void repeatedIdenticalBoundsDoNotDisturbARunInProgress() {
        GameEngine e = new GameEngine(new Rng(1));
        e.setBounds(1080, 2046);
        e.start(0L);
        e.setTargetX(200f);
        float before = e.playerY();

        for (int i = 0; i < 10; i++) {
            e.setBounds(1080, 2046);
        }

        assertEquals(before, e.playerY(), 0.001f,
                "setBounds with unchanged bounds must not reposition the player mid-run");
    }
}
