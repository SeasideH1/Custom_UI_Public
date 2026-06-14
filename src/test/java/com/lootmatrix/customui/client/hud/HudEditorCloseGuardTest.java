package com.lootmatrix.customui.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lootmatrix.customui.hud.HudTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudEditorCloseGuardTest {

    private static final Gson GSON = new GsonBuilder().create();

    @Test
    void snapshotTracksEditedIdTextEvenBeforeSave() {
        HudTemplate template = new HudTemplate();
        template.id = "customui:original";

        String savedSnapshot = HudEditorCloseGuard.snapshotJson(template, template.id, GSON);
        String editedSnapshot = HudEditorCloseGuard.snapshotJson(template, "customui:renamed", GSON);

        assertTrue(HudEditorCloseGuard.hasUnsavedChanges(savedSnapshot, editedSnapshot));
    }

    @Test
    void matchingSnapshotsAreNotUnsaved() {
        HudTemplate template = new HudTemplate();
        template.id = "customui:test";

        String snapshot = HudEditorCloseGuard.snapshotJson(template, template.id, GSON);

        assertFalse(HudEditorCloseGuard.hasUnsavedChanges(snapshot, snapshot));
    }

    @Test
    void templateContentChangesAreUnsaved() {
        HudTemplate template = new HudTemplate();
        template.id = "customui:test";

        String savedSnapshot = HudEditorCloseGuard.snapshotJson(template, template.id, GSON);
        template.loop = true;
        String editedSnapshot = HudEditorCloseGuard.snapshotJson(template, template.id, GSON);

        assertTrue(HudEditorCloseGuard.hasUnsavedChanges(savedSnapshot, editedSnapshot));
    }
}
