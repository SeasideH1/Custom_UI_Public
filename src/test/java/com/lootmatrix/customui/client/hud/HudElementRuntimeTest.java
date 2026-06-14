package com.lootmatrix.customui.client.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudElementRuntimeTest {

    @Test
    void smoothFractionUsesConfiguredDuration() {
        assertEquals(0.25f, HudElementRuntime.smoothFractionForElapsed(0f, 1f, 0.5f, 2f), 0.0001f);
        assertEquals(0.75f, HudElementRuntime.smoothFractionForElapsed(0.5f, 1f, 0.5f, 1f), 0.0001f);
    }

    @Test
    void smoothFractionSnapsWhenDurationHasElapsedOrIsDisabled() {
        assertEquals(1f, HudElementRuntime.smoothFractionForElapsed(0f, 1f, 2f, 2f), 0.0001f);
        assertEquals(1f, HudElementRuntime.smoothFractionForElapsed(0f, 1f, 0f, 0f), 0.0001f);
    }
}
