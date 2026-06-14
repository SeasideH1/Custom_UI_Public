package com.lootmatrix.customui.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudElementProjectionTest {

    @Test
    void projectionWritesWorldCoordinatesEvenWhenDynamicAnchorIsPresent() {
        HudElement element = new HudElement();
        element.hasProjection = true;
        element.worldX = 12.5;
        element.worldY = 64.0;
        element.worldZ = -7.25;
        element.worldAnchor = "capture_zone:zone1";

        JsonObject projection = element.toJson().getAsJsonObject("projection");

        assertTrue(projection.has("world"));
        JsonArray world = projection.getAsJsonArray("world");
        assertEquals(12.5, world.get(0).getAsDouble(), 0.0001);
        assertEquals(64.0, world.get(1).getAsDouble(), 0.0001);
        assertEquals(-7.25, world.get(2).getAsDouble(), 0.0001);
        assertEquals("capture_zone:zone1", projection.get("worldAnchor").getAsString());
    }
}
