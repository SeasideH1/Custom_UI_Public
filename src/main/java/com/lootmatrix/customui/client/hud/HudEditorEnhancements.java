package com.lootmatrix.customui.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lootmatrix.customui.hud.HudAnchor;
import com.lootmatrix.customui.hud.HudEasing;
import com.lootmatrix.customui.hud.HudElement;
import com.lootmatrix.customui.hud.HudKeyframe;
import com.lootmatrix.customui.hud.HudTemplate;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Session-scoped editor utilities: alignment, color history, prefabs, animation snippets, snapshots.
 */
@OnlyIn(Dist.CLIENT)
public final class HudEditorEnhancements {

    private static final Gson GSON = new GsonBuilder().create();
    private static final int RECENT_MAX = 8;
    private static final int SNAPSHOT_MAX = 3;

    private static final Deque<Integer> RECENT_COLORS = new ArrayDeque<>();
    private static final Deque<String> RECENT_TEXTURES = new ArrayDeque<>();
    private static final Deque<String> TEMPLATE_SNAPSHOTS = new ArrayDeque<>();

    /** Preset swatches for the color picker (ARGB). */
    public static final int[] COLOR_PALETTE = {
            0xFFFFFFFF, 0xFF000000, 0xFF4FC3F7, 0xFF7E57C2, 0xFF44FF44, 0xFFFF5252,
            0xFFFFC640, 0xFFE6EDF3, 0xFF9AA7B0, 0xFF2A3540, 0xFF101418, 0x8033B5E5,
            0x80FFFFFF, 0xFF2196F3, 0xFF4CAF50, 0xFFFF9800, 0xFFE91E63, 0xFF9C27B0,
            0xFF00BCD4, 0xFF8BC34A, 0xFFFFEB3B, 0xFF795548, 0xFF607D8B, 0xFF37474F,
    };

    public static final int[][] RESOLUTION_PRESETS = {
            {0, 0}, {1920, 1080}, {1280, 720}, {2560, 1440}, {854, 480},
    };

    public static final String[] RESOLUTION_LABELS = {
            "当前窗口", "1920×1080", "1280×720", "2560×1440", "854×480",
    };

    public static final String[] DATA_SOURCE_PRESETS = {
            "entity:@e[limit=1,sort=nearest,distance=..30]:health",
            "entity:@e[limit=1,sort=nearest,distance=..30]:name",
            "entity:@e[limit=1,sort=nearest,distance=..30]:distance",
            "scoreboard:sidebar",
    };

    public static final String[] DATA_SOURCE_LABELS = {
            "最近实体血量", "最近实体名", "最近实体距离", "侧边栏记分",
    };

    /** Prefab id → builder callback name handled in screen. */
    public static final String[][] PREFAB_ENTRIES = {
            {"血条底+条", "PREFAB_HP"},
            {"标题条", "PREFAB_TITLE"},
            {"圆角面板", "PREFAB_PANEL"},
            {"按钮底", "PREFAB_BUTTON"},
    };

    public static final String[][] ANIM_SNIPPETS = {
            {"淡入 20t", "FADE_IN"},
            {"淡出 20t", "FADE_OUT"},
            {"从左滑入", "SLIDE_LEFT"},
            {"缩放弹出", "SCALE_POP"},
    };

    public enum AlignMode { LEFT, CENTER_H, RIGHT, TOP, CENTER_V, BOTTOM, DISTRIBUTE_H, DISTRIBUTE_V }

    private HudEditorEnhancements() {}

    public static void rememberColor(int argb) {
        RECENT_COLORS.remove(argb);
        RECENT_COLORS.addFirst(argb);
        while (RECENT_COLORS.size() > RECENT_MAX) {
            RECENT_COLORS.removeLast();
        }
    }

    public static void rememberTexture(String path) {
        if (path == null || path.isEmpty()) return;
        RECENT_TEXTURES.remove(path);
        RECENT_TEXTURES.addFirst(path);
        while (RECENT_TEXTURES.size() > RECENT_MAX) {
            RECENT_TEXTURES.removeLast();
        }
    }

    public static List<Integer> recentColors() {
        return List.copyOf(RECENT_COLORS);
    }

    public static List<String> recentTextures() {
        return List.copyOf(RECENT_TEXTURES);
    }

    public static void pushTemplateSnapshot(HudTemplate template) {
        String json = GSON.toJson(template.toJson());
        TEMPLATE_SNAPSHOTS.remove(json);
        TEMPLATE_SNAPSHOTS.addFirst(json);
        while (TEMPLATE_SNAPSHOTS.size() > SNAPSHOT_MAX) {
            TEMPLATE_SNAPSHOTS.removeLast();
        }
    }

    @Nullable
    public static HudTemplate popTemplateSnapshot(int index) {
        if (index < 0 || index >= TEMPLATE_SNAPSHOTS.size()) return null;
        int i = 0;
        for (String json : TEMPLATE_SNAPSHOTS) {
            if (i++ == index) {
                try {
                    JsonObject obj = GSON.fromJson(json, JsonObject.class);
                    String id = obj.has("id") ? obj.get("id").getAsString() : "customui:snapshot";
                    return HudTemplate.fromJson(id, obj);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static int snapshotCount() {
        return TEMPLATE_SNAPSHOTS.size();
    }

    @Nullable
    public static Integer parseHexColor(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6 && t.length() != 8) return null;
        try {
            long v = Long.parseLong(t, 16);
            if (t.length() == 6) v |= 0xFF000000L;
            return (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static float[] elementBox(HudElement e, float renderTime, float[] editRect, float[] out) {
        HudTemplateOverlayRenderer.resolveLocalBoxInRect(e, renderTime,
                editRect[0], editRect[1], editRect[2], editRect[3], out);
        return out;
    }

    public static void alignElements(List<HudElement> elements, AlignMode mode, float renderTime,
                                      float[] editRect, float[] scratch) {
        if (elements.size() < 2 && mode != AlignMode.LEFT) return;
        float minL = Float.MAX_VALUE, minT = Float.MAX_VALUE;
        float maxR = -Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
        List<ElementLayout> layouts = new ArrayList<>(elements.size());
        for (HudElement e : elements) {
            elementBox(e, renderTime, editRect, scratch);
            float l = scratch[0], t = scratch[1], r = scratch[0] + scratch[2], b = scratch[1] + scratch[3];
            layouts.add(new ElementLayout(e, l, t, r, b, scratch[2], scratch[3]));
            minL = Math.min(minL, l);
            minT = Math.min(minT, t);
            maxR = Math.max(maxR, r);
            maxB = Math.max(maxB, b);
        }
        float spanW = maxR - minL;
        float spanH = maxB - minT;
        for (int i = 0; i < layouts.size(); i++) {
            ElementLayout layout = layouts.get(i);
            HudElement e = layout.element;
            float cx = layout.left + layout.width / 2f;
            float cy = layout.top + layout.height / 2f;
            switch (mode) {
                case LEFT -> e.x += minL - layout.left;
                case RIGHT -> e.x += maxR - layout.right;
                case CENTER_H -> e.x += (minL + spanW / 2f) - cx;
                case TOP -> e.y += minT - layout.top;
                case BOTTOM -> e.y += maxB - layout.bottom;
                case CENTER_V -> e.y += (minT + spanH / 2f) - cy;
                default -> { }
            }
        }
        if (mode == AlignMode.DISTRIBUTE_H && elements.size() >= 3) {
            distributeHorizontally(layouts);
        }
        if (mode == AlignMode.DISTRIBUTE_V && elements.size() >= 3) {
            distributeVertically(layouts);
        }
    }

    /** Snap element center to grid or sibling edges. Returns adjusted dx/dy deltas. */
    public static void applySnap(float[] targetCxCy, float dx, float dy, List<HudElement> others,
                                 HudElement self, float renderTime, float[] editRect, float[] scratch,
                                 float gridSize, boolean gridSnap, boolean elementSnap, float threshold) {
        float cx = targetCxCy[0] + dx;
        float cy = targetCxCy[1] + dy;
        float selfScale = self.track(HudKeyframe.PROP_SCALE).evaluate(renderTime, self.scale);
        float selfW = self.w * selfScale;
        float selfH = self.h * selfScale;
        if (gridSnap && gridSize > 0) {
            cx = Math.round(cx / gridSize) * gridSize;
            cy = Math.round(cy / gridSize) * gridSize;
        }
        if (elementSnap) {
            float bestCx = cx;
            float bestCy = cy;
            float bestDx = threshold + 1f;
            float bestDy = threshold + 1f;
            for (HudElement o : others) {
                if (o == self) continue;
                elementBox(o, renderTime, editRect, scratch);
                float otherLeft = scratch[0];
                float otherTop = scratch[1];
                float otherRight = scratch[0] + scratch[2];
                float otherBottom = scratch[1] + scratch[3];
                float otherCx = otherLeft + scratch[2] / 2f;
                float otherCy = otherTop + scratch[3] / 2f;

                float selfLeft = cx - selfW / 2f;
                float selfRight = cx + selfW / 2f;
                float selfTop = cy - selfH / 2f;
                float selfBottom = cy + selfH / 2f;

                float snappedX = snapCandidate(cx, otherCx, threshold, bestDx);
                if (!Float.isNaN(snappedX)) {
                    bestCx = snappedX;
                    bestDx = snapDistance(cx, bestCx);
                }
                snappedX = snapCandidate(cx, otherLeft + selfW / 2f, threshold, bestDx);
                if (!Float.isNaN(snappedX)) {
                    bestCx = snappedX;
                    bestDx = snapDistance(cx, bestCx);
                }
                snappedX = snapCandidate(cx, otherRight - selfW / 2f, threshold, bestDx);
                if (!Float.isNaN(snappedX)) {
                    bestCx = snappedX;
                    bestDx = snapDistance(cx, bestCx);
                }
                snappedX = snapCandidate(selfLeft, otherLeft, threshold, bestDx, selfW / 2f);
                if (!Float.isNaN(snappedX)) {
                    bestCx = snappedX;
                    bestDx = snapDistance(cx, bestCx);
                }
                snappedX = snapCandidate(selfLeft, otherRight, threshold, bestDx, selfW / 2f);
                if (!Float.isNaN(snappedX)) {
                    bestCx = snappedX;
                    bestDx = snapDistance(cx, bestCx);
                }
                snappedX = snapCandidate(selfRight, otherLeft, threshold, bestDx, -selfW / 2f);
                if (!Float.isNaN(snappedX)) {
                    bestCx = snappedX;
                    bestDx = snapDistance(cx, bestCx);
                }
                snappedX = snapCandidate(selfRight, otherRight, threshold, bestDx, -selfW / 2f);
                if (!Float.isNaN(snappedX)) {
                    bestCx = snappedX;
                    bestDx = snapDistance(cx, bestCx);
                }

                float snappedY = snapCandidate(cy, otherCy, threshold, bestDy);
                if (!Float.isNaN(snappedY)) {
                    bestCy = snappedY;
                    bestDy = snapDistance(cy, bestCy);
                }
                snappedY = snapCandidate(cy, otherTop + selfH / 2f, threshold, bestDy);
                if (!Float.isNaN(snappedY)) {
                    bestCy = snappedY;
                    bestDy = snapDistance(cy, bestCy);
                }
                snappedY = snapCandidate(cy, otherBottom - selfH / 2f, threshold, bestDy);
                if (!Float.isNaN(snappedY)) {
                    bestCy = snappedY;
                    bestDy = snapDistance(cy, bestCy);
                }
                snappedY = snapCandidate(selfTop, otherTop, threshold, bestDy, selfH / 2f);
                if (!Float.isNaN(snappedY)) {
                    bestCy = snappedY;
                    bestDy = snapDistance(cy, bestCy);
                }
                snappedY = snapCandidate(selfTop, otherBottom, threshold, bestDy, selfH / 2f);
                if (!Float.isNaN(snappedY)) {
                    bestCy = snappedY;
                    bestDy = snapDistance(cy, bestCy);
                }
                snappedY = snapCandidate(selfBottom, otherTop, threshold, bestDy, -selfH / 2f);
                if (!Float.isNaN(snappedY)) {
                    bestCy = snappedY;
                    bestDy = snapDistance(cy, bestCy);
                }
                snappedY = snapCandidate(selfBottom, otherBottom, threshold, bestDy, -selfH / 2f);
                if (!Float.isNaN(snappedY)) {
                    bestCy = snappedY;
                    bestDy = snapDistance(cy, bestCy);
                }
            }
            cx = bestCx;
            cy = bestCy;
        }
        targetCxCy[0] = cx;
        targetCxCy[1] = cy;
    }

    public static void applyAnimationSnippet(HudElement e, String kind) {
        e.keyframes.clear();
        switch (kind) {
            case "FADE_IN" -> {
                e.keyframes.add(kf(0, HudKeyframe.PROP_OPACITY, 0f));
                e.keyframes.add(kf(20, HudKeyframe.PROP_OPACITY, 1f));
            }
            case "FADE_OUT" -> {
                e.keyframes.add(kf(0, HudKeyframe.PROP_OPACITY, 1f));
                e.keyframes.add(kf(20, HudKeyframe.PROP_OPACITY, 0f));
            }
            case "SLIDE_LEFT" -> {
                e.keyframes.add(kf(0, HudKeyframe.PROP_X, e.x - 80f));
                e.keyframes.add(kf(0, HudKeyframe.PROP_OPACITY, 0f));
                e.keyframes.add(kf(20, HudKeyframe.PROP_X, e.x));
                e.keyframes.add(kf(20, HudKeyframe.PROP_OPACITY, 1f));
            }
            case "SCALE_POP" -> {
                e.keyframes.add(kf(0, HudKeyframe.PROP_SCALE, 0.2f));
                e.keyframes.add(kf(0, HudKeyframe.PROP_OPACITY, 0f));
                e.keyframes.add(kf(15, HudKeyframe.PROP_SCALE, 1.1f));
                e.keyframes.add(kf(22, HudKeyframe.PROP_SCALE, 1f));
                e.keyframes.add(kf(22, HudKeyframe.PROP_OPACITY, 1f));
            }
            default -> { return; }
        }
        e.compileTracks();
    }

    private static HudKeyframe kf(int tick, int prop, float value) {
        HudKeyframe k = new HudKeyframe();
        k.tick = tick;
        k.set(prop, value);
        k.easing = HudEasing.EASE_OUT_QUAD;
        return k;
    }

    public static List<HudElement> buildPrefab(String kind, float cx, float cy) {
        List<HudElement> list = new ArrayList<>();
        switch (kind) {
            case "PREFAB_HP" -> {
                HudElement bg = rect(cx - 50, cy - 6, 100, 12, 0xAA000000);
                HudElement bar = rect(cx - 48, cy - 4, 96, 8, 0xFF44FF44);
                bar.dataSource = "entity:@e[limit=1,sort=nearest,distance=..30]:health_pct";
                bar.type = HudElement.Type.PROGRESS;
                bar.max = 100f;
                bar.value = 100f;
                bar.direction = "horizontal";
                list.add(bg);
                list.add(bar);
            }
            case "PREFAB_TITLE" -> {
                HudElement bg = rounded(cx - 80, cy - 14, 160, 28, 0xC0101418, 6f);
                HudElement txt = text(cx, cy - 4, "标题文本", 0xFFE6EDF3, 14);
                txt.anchor = HudAnchor.CENTER;
                list.add(bg);
                list.add(txt);
            }
            case "PREFAB_PANEL" -> list.add(rounded(cx - 60, cy - 40, 120, 80, 0xE0101418, 8f));
            case "PREFAB_BUTTON" -> {
                list.add(rounded(cx - 40, cy - 12, 80, 24, 0xFF2A3540, 4f));
                HudElement lbl = text(cx, cy - 2, "按钮", 0xFFFFFFFF, 12);
                lbl.anchor = HudAnchor.CENTER;
                list.add(lbl);
            }
            default -> { }
        }
        for (HudElement e : list) {
            e.anchor = HudAnchor.CENTER;
        }
        return list;
    }

    private static HudElement rect(float x, float y, float w, float h, int fill) {
        HudElement e = new HudElement();
        e.type = HudElement.Type.RECT;
        e.x = x; e.y = y; e.w = w; e.h = h;
        e.fillColor = fill;
        return e;
    }

    private static HudElement rounded(float x, float y, float w, float h, int fill, float r) {
        HudElement e = rect(x, y, w, h, fill);
        e.type = HudElement.Type.ROUNDED_RECT;
        e.cornerRadius = r;
        return e;
    }

    private static HudElement text(float x, float y, String t, int color, int size) {
        HudElement e = new HudElement();
        e.type = HudElement.Type.TEXT;
        e.x = x; e.y = y;
        e.text = t;
        e.textColor = color;
        e.fontSize = size;
        e.w = 80; e.h = 20;
        return e;
    }

    public static String anchorLabel(HudAnchor a) {
        return a.toJsonName();
    }

    public static HudAnchor nextAnchor(HudAnchor a) {
        return HudAnchor.byId((a.ordinal() + 1) % HudAnchor.values().length);
    }

    public static HudEasing nextEasing(HudEasing e) {
        return HudEasing.byId((e.ordinal() + 1) % HudEasing.values().length);
    }

    public static String cycleLabel(String[] options, String current) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(current)) {
                return options[(i + 1) % options.length];
            }
        }
        return options[0];
    }

    public static int indexOfOption(String[] options, String current) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(current)) return i;
        }
        return 0;
    }

    public static String optionAt(String[] options, int index) {
        if (options.length == 0) return "";
        return options[Math.floorMod(index, options.length)];
    }

    /** Resize handle index: 0=NW,1=N,2=NE,3=E,4=SE,5=S,6=SW,7=W, -1=none */
    public static int hitResizeHandle(float mx, float my, float[] box, float scale, float handleSize) {
        float hs = handleSize / Math.max(0.01f, scale);
        float l = box[0], t = box[1], r = box[0] + box[2], b = box[1] + box[3];
        float cx = (l + r) / 2f, cy = (t + b) / 2f;
        float[][] pts = {
                {l, t}, {cx, t}, {r, t}, {r, cy}, {r, b}, {cx, b}, {l, b}, {l, cy}
        };
        for (int i = 0; i < 8; i++) {
            if (Math.abs(mx - pts[i][0]) <= hs && Math.abs(my - pts[i][1]) <= hs) return i;
        }
        return -1;
    }

    public static void applyResize(HudElement e, int handle, float dx, float dy, float startW, float startH,
                                   float startX, float startY, boolean uniform) {
        float[] box = new float[4];
        resizeBox(box, handle, dx, dy, startW, startH, startX, startY, uniform);
        e.x = box[0];
        e.y = box[1];
        e.w = box[2];
        e.h = box[3];
    }

    public static void resizeBox(float[] out, int handle, float dx, float dy, float startW, float startH,
                                 float startX, float startY, boolean uniform) {
        float left = startX;
        float top = startY;
        float right = startX + startW;
        float bottom = startY + startH;
        switch (handle) {
            case 0 -> { left = startX + dx; top = startY + dy; }
            case 1 -> top = startY + dy;
            case 2 -> { right = startX + startW + dx; top = startY + dy; }
            case 3 -> right = startX + startW + dx;
            case 4 -> { right = startX + startW + dx; bottom = startY + startH + dy; }
            case 5 -> bottom = startY + startH + dy;
            case 6 -> { left = startX + dx; bottom = startY + startH + dy; }
            case 7 -> left = startX + dx;
            default -> { return; }
        }
        if (uniform && (handle == 0 || handle == 2 || handle == 4 || handle == 6)) {
            float aspect = Math.max(0.01f, startW / Math.max(0.01f, startH));
            float widthScale = Math.abs((right - left) / Math.max(0.01f, startW) - 1f);
            float heightScale = Math.abs((bottom - top) / Math.max(0.01f, startH) - 1f);
            boolean useWidth = widthScale >= heightScale;
            float targetW = Math.max(1f, useWidth ? Math.abs(right - left) : Math.abs(bottom - top) * aspect);
            float targetH = Math.max(1f, targetW / aspect);
            switch (handle) {
                case 0 -> { left = startX + startW - targetW; top = startY + startH - targetH; }
                case 2 -> { right = startX + targetW; top = startY + startH - targetH; }
                case 4 -> { right = startX + targetW; bottom = startY + targetH; }
                case 6 -> { left = startX + startW - targetW; bottom = startY + targetH; }
                default -> { }
            }
        }
        float w = Math.max(1f, Math.abs(right - left));
        float h = Math.max(1f, Math.abs(bottom - top));
        out[0] = Math.min(left, right);
        out[1] = Math.min(top, bottom);
        out[2] = w;
        out[3] = h;
    }

    @Nullable
    public static HudTemplate importTemplateJson(@Nullable HudTemplate current, String json, boolean merge) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) return null;
            String fallbackId = current != null && current.id != null && !current.id.isEmpty()
                    ? current.id
                    : "customui:clipboard";
            HudTemplate imported = HudTemplate.fromJson(fallbackId, obj);
            if (!merge || current == null) {
                return imported;
            }
            HudTemplate merged = current.copy();
            Set<String> usedIds = new HashSet<>();
            collectElementIds(merged.elements, usedIds);
            for (HudElement element : imported.elements) {
                HudElement copy = HudElement.fromJson(element.toJson());
                dedupeElementIds(copy, usedIds);
                merged.elements.add(copy);
            }
            merged.markRenderOrderDirty();
            return merged;
        } catch (Exception e) {
            return null;
        }
    }

    public static String keyframeClipboardJson(HudKeyframe kf) {
        return GSON.toJson(kf.toJson());
    }

    @Nullable
    public static HudKeyframe keyframeFromClipboard(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            return HudKeyframe.fromJson(obj);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean rowMatchesFilter(String label, String group, String query) {
        if (query == null || query.isEmpty()) return true;
        String q = query.toLowerCase(Locale.ROOT);
        return label.toLowerCase(Locale.ROOT).contains(q)
                || (group != null && group.toLowerCase(Locale.ROOT).contains(q));
    }

    private static float snapCandidate(float current, float candidate, float threshold, float bestDistance) {
        return snapCandidate(current, candidate, threshold, bestDistance, 0f);
    }

    private static float snapCandidate(float current, float candidate, float threshold,
                                       float bestDistance, float centerOffset) {
        float dist = Math.abs(current - candidate);
        if (dist <= threshold && dist < bestDistance) {
            return candidate + centerOffset;
        }
        return Float.NaN;
    }

    private static float snapDistance(float current, float snapped) {
        return Math.abs(current - snapped);
    }

    private static void collectElementIds(List<HudElement> elements, Set<String> ids) {
        for (HudElement element : elements) {
            if (element.id != null && !element.id.isEmpty()) {
                ids.add(element.id);
            }
            collectElementIds(element.children, ids);
        }
    }

    private static void dedupeElementIds(HudElement element, Set<String> usedIds) {
        if (element.id != null && !element.id.isEmpty()) {
            element.id = uniqueId(element.id, usedIds);
            usedIds.add(element.id);
        }
        for (HudElement child : element.children) {
            dedupeElementIds(child, usedIds);
        }
    }

    private static String uniqueId(String baseId, Set<String> usedIds) {
        if (!usedIds.contains(baseId)) {
            return baseId;
        }
        int suffix = 2;
        while (usedIds.contains(baseId + "_" + suffix)) {
            suffix++;
        }
        return baseId + "_" + suffix;
    }

    private static void distributeHorizontally(List<ElementLayout> layouts) {
        List<ElementLayout> sorted = new ArrayList<>(layouts);
        sorted.sort((a, b) -> Float.compare(a.left, b.left));
        float totalWidth = 0f;
        for (ElementLayout layout : sorted) {
            totalWidth += layout.width;
        }
        float gap = (sorted.get(sorted.size() - 1).right - sorted.get(0).left - totalWidth)
                / (sorted.size() - 1);
        float cursor = sorted.get(0).right;
        for (int i = 1; i < sorted.size(); i++) {
            ElementLayout layout = sorted.get(i);
            float newLeft = cursor + gap;
            layout.element.x += newLeft - layout.left;
            cursor = newLeft + layout.width;
        }
    }

    private static void distributeVertically(List<ElementLayout> layouts) {
        List<ElementLayout> sorted = new ArrayList<>(layouts);
        sorted.sort((a, b) -> Float.compare(a.top, b.top));
        float totalHeight = 0f;
        for (ElementLayout layout : sorted) {
            totalHeight += layout.height;
        }
        float gap = (sorted.get(sorted.size() - 1).bottom - sorted.get(0).top - totalHeight)
                / (sorted.size() - 1);
        float cursor = sorted.get(0).bottom;
        for (int i = 1; i < sorted.size(); i++) {
            ElementLayout layout = sorted.get(i);
            float newTop = cursor + gap;
            layout.element.y += newTop - layout.top;
            cursor = newTop + layout.height;
        }
    }

    private record ElementLayout(HudElement element, float left, float top, float right, float bottom,
                                 float width, float height) {}
}
