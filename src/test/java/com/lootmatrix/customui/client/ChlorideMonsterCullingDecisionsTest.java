package com.lootmatrix.customui.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChlorideMonsterCullingDecisionsTest {

    @Test
    void visibleMonsterInsideRenderDistanceRenders() {
        assertTrue(ChlorideMonsterCullingDecisions.shouldRenderMonster(true, 8, 32.0, 0.0, 0.0));
    }

    @Test
    void offscreenMonsterDoesNotRenderEvenWhenNearby() {
        assertFalse(ChlorideMonsterCullingDecisions.shouldRenderMonster(false, 8, 8.0, 0.0, 8.0));
    }

    @Test
    void distantMonsterDoesNotRenderEvenWhenVisible() {
        assertFalse(ChlorideMonsterCullingDecisions.shouldRenderMonster(true, 4, 80.0, 0.0, 0.0));
    }
}
