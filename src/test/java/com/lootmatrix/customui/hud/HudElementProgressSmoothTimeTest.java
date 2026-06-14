package com.lootmatrix.customui.hud;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudElementProgressSmoothTimeTest {

    @Test
    void progressReadsSmoothTimeBeforeLegacySmoothSpeed() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "progress");
        json.addProperty("smoothSpeed", 0.75f);
        json.addProperty("smoothTime", 0.4f);

        HudElement element = HudElement.fromJson(json);

        assertEquals(0.4f, element.progressSmoothSpeed, 0.0001f);
    }

    @Test
    void progressWritesSmoothTimeInsteadOfLegacySmoothSpeed() {
        HudElement element = new HudElement();
        element.type = HudElement.Type.PROGRESS;
        element.progressSmoothSpeed = 0.4f;

        JsonObject json = element.toJson();

        assertTrue(json.has("smoothTime"));
        assertEquals(0.4f, json.get("smoothTime").getAsFloat(), 0.0001f);
        assertFalse(json.has("smoothSpeed"));
    }
}
