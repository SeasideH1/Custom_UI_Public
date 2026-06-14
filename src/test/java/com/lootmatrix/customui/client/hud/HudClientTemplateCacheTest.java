package com.lootmatrix.customui.client.hud;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HudClientTemplateCacheTest {

    @Test
    void applySyncNotifiesChangeListeners() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AutoCloseable listener = HudClientTemplateCache.addChangeListener(calls::incrementAndGet);
        try {
            HudClientTemplateCache.applySync(true, List.of("""
                    {"format":2,"id":"customui:test_refresh","elements":[]}
                    """));

            assertEquals(1, calls.get());
        } finally {
            listener.close();
            HudClientTemplateCache.clear();
        }
    }

    @Test
    void editingStaticWorldCoordinateClearsDynamicAnchor() {
        com.lootmatrix.customui.hud.HudElement element = new com.lootmatrix.customui.hud.HudElement();
        element.worldAnchor = "capture_zone:zone1";

        HudEditorScreen.setStaticWorldCoordinate(element, 0, 12.5);

        assertEquals(12.5, element.worldX, 0.0001);
        assertNull(element.worldAnchor);
    }
}
