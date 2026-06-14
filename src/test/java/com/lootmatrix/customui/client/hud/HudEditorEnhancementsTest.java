package com.lootmatrix.customui.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lootmatrix.customui.hud.HudAnchor;
import com.lootmatrix.customui.hud.HudElement;
import com.lootmatrix.customui.hud.HudTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HudEditorEnhancementsTest {

    private static final Gson GSON = new GsonBuilder().create();

    @Test
    void distributeHorizontalUsesVisualOrderInsteadOfSelectionOrder() {
        HudElement right = topLeftRect("right", 200f, 20f, 20f, 10f);
        HudElement left = topLeftRect("left", 0f, 20f, 20f, 10f);
        HudElement middle = topLeftRect("middle", 100f, 20f, 20f, 10f);

        List<HudElement> selected = List.of(right, left, middle);

        HudEditorEnhancements.alignElements(
                selected,
                HudEditorEnhancements.AlignMode.DISTRIBUTE_H,
                0f,
                new float[]{0f, 0f, 300f, 200f},
                new float[4]
        );

        assertEquals(0f, left.x, 0.0001f);
        assertEquals(100f, middle.x, 0.0001f);
        assertEquals(200f, right.x, 0.0001f);
    }

    @Test
    void applySnapCanAlignDraggedElementEdgeToSiblingEdge() {
        HudElement self = topLeftRect("self", 60f, 20f, 20f, 10f);
        HudElement sibling = topLeftRect("sibling", 100f, 20f, 30f, 10f);
        float[] targetCenter = {self.x + self.w / 2f, self.y + self.h / 2f};

        HudEditorEnhancements.applySnap(
                targetCenter,
                38f,
                0f,
                List.of(sibling),
                self,
                0f,
                new float[]{0f, 0f, 300f, 200f},
                new float[4],
                0f,
                false,
                true,
                5f
        );

        assertEquals(110f, targetCenter[0], 0.0001f);
    }

    @Test
    void uniformResizeFromTopLeftKeepsOppositeCornerPinned() {
        HudElement element = topLeftRect("resize", 10f, 20f, 100f, 50f);

        HudEditorEnhancements.applyResize(element, 0, 10f, 30f, 100f, 50f, 10f, 20f, true);

        assertEquals(110f, element.x + element.w, 0.0001f);
        assertEquals(70f, element.y + element.h, 0.0001f);
        assertEquals(2f, element.w / element.h, 0.0001f);
    }

    @Test
    void importTemplateJsonOverwriteReplacesTemplateContent() {
        HudTemplate current = new HudTemplate();
        current.id = "customui:current";
        current.elements.add(topLeftRect("old", 0f, 0f, 10f, 10f));

        HudTemplate incoming = new HudTemplate();
        incoming.id = "customui:incoming";
        incoming.lifetime = 40;
        incoming.elements.add(topLeftRect("new_one", 10f, 0f, 10f, 10f));
        incoming.elements.add(topLeftRect("new_two", 20f, 0f, 10f, 10f));

        HudTemplate imported = HudEditorEnhancements.importTemplateJson(
                current,
                GSON.toJson(incoming.toJson()),
                false
        );

        assertEquals("customui:incoming", imported.id);
        assertEquals(40, imported.lifetime);
        assertEquals(2, imported.elements.size());
        assertEquals("new_one", imported.elements.get(0).id);
    }

    @Test
    void importTemplateJsonMergeKeepsCurrentTemplateAndDeduplicatesIds() {
        HudTemplate current = new HudTemplate();
        current.id = "customui:current";
        current.elements.add(topLeftRect("hp_bar", 0f, 0f, 10f, 10f));

        HudTemplate incoming = new HudTemplate();
        incoming.id = "customui:incoming";
        incoming.elements.add(topLeftRect("hp_bar", 10f, 0f, 10f, 10f));
        incoming.elements.add(topLeftRect("name", 20f, 0f, 10f, 10f));

        HudTemplate imported = HudEditorEnhancements.importTemplateJson(
                current,
                GSON.toJson(incoming.toJson()),
                true
        );

        assertNotNull(imported);
        assertEquals("customui:current", imported.id);
        assertEquals(3, imported.elements.size());
        assertEquals("hp_bar", imported.elements.get(0).id);
        assertEquals("hp_bar_2", imported.elements.get(1).id);
        assertEquals("name", imported.elements.get(2).id);
    }

    private static HudElement topLeftRect(String id, float x, float y, float w, float h) {
        HudElement element = new HudElement();
        element.id = id;
        element.type = HudElement.Type.RECT;
        element.anchor = HudAnchor.TOP_LEFT;
        element.origin = HudAnchor.TOP_LEFT;
        element.x = x;
        element.y = y;
        element.w = w;
        element.h = h;
        return element;
    }
}
