package com.lootmatrix.customui.client.hud;

import com.google.gson.Gson;
import com.lootmatrix.customui.hud.HudTemplate;

import java.util.Objects;

final class HudEditorCloseGuard {

    private HudEditorCloseGuard() {
    }

    static String snapshotJson(HudTemplate template, String idText, Gson gson) {
        HudTemplate snapshot = template.copy();
        snapshot.id = idText == null ? "" : idText.trim();
        return gson.toJson(snapshot.toJson());
    }

    static boolean hasUnsavedChanges(String savedSnapshotJson, String currentSnapshotJson) {
        return !Objects.equals(savedSnapshotJson, currentSnapshotJson);
    }
}
