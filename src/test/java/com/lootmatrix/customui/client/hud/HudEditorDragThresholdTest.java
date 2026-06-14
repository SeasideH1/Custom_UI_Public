package com.lootmatrix.customui.client.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudEditorDragThresholdTest {

    @Test
    void staysPendingWhileBothAxesRemainWithinThreshold() {
        assertFalse(HudEditorDragThreshold.exceedsDragSlop(100.0, 100.0, 104.0, 104.0, 4.0));
    }

    @Test
    void startsDragWhenHorizontalDeltaExceedsThreshold() {
        assertTrue(HudEditorDragThreshold.exceedsDragSlop(100.0, 100.0, 104.1, 100.0, 4.0));
    }

    @Test
    void startsDragWhenVerticalDeltaExceedsThreshold() {
        assertTrue(HudEditorDragThreshold.exceedsDragSlop(100.0, 100.0, 100.0, 104.1, 4.0));
    }
}
