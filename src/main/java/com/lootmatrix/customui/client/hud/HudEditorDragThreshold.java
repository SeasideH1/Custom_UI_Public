package com.lootmatrix.customui.client.hud;

final class HudEditorDragThreshold {

    private HudEditorDragThreshold() {
    }

    static boolean exceedsDragSlop(double pressMouseX, double pressMouseY,
                                   double mouseX, double mouseY,
                                   double threshold) {
        return Math.abs(mouseX - pressMouseX) > threshold
                || Math.abs(mouseY - pressMouseY) > threshold;
    }
}
