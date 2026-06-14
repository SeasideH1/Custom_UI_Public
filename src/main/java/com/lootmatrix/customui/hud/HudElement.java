package com.lootmatrix.customui.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One element of a HUD template: base transform (9-grid anchor + 9-grid origin,
 * offset, rotation, scale, opacity, z-index) plus per-type payload and an
 * optional keyframe animation (compiled into per-property {@link HudTrack}s).
 */
public class HudElement {

    public enum Type {
        TEXT,          // native JSON text component (supports bitmap-font char images <=256x256)
        IMAGE,         // direct texture blit, arbitrary resolution
        RECT,          // solid color quad
        CIRCLE,        // filled ellipse or ring (ringThickness > 0)
        TRIANGLE,      // filled triangle pointing up/down/left/right
        BORDER,        // hollow rectangle outline
        LINE,          // horizontal bar centered in the box (rotate for any angle)
        ROUNDED_RECT,  // solid quad with rounded corners
        GRADIENT_RECT, // two-color linear gradient quad
        ARC,           // pie slice or arc band (ringThickness > 0), angles clockwise from 12 o'clock
        PROGRESS,      // progress bar, optionally bound to a data source
        TEMPLATE,      // embeds another template (advanced templates reuse simpler ones)
        GROUP,         // inline children sharing this element's transform/keyframes/projection
        STAT;          // live performance/network metric: numeric readout and/or line chart

        public static Type fromString(String s) {
            try { return valueOf(s.toUpperCase()); } catch (Exception e) { return TEXT; }
        }
    }

    // ---- Core ----
    public Type type = Type.TEXT;
    public String id = "";
    public HudAnchor anchor = HudAnchor.CENTER;
    public HudAnchor origin = HudAnchor.CENTER;
    public float x, y;          // offset from screen anchor, GUI-scaled px
    public float w = 50, h = 20;
    public float scale = 1f;
    public float rotation = 0f; // degrees, around origin point
    public float opacity = 1f;
    public int zIndex = 0;
    public boolean visible = true;
    /** Reserved for future GUI mouse interaction support (not implemented yet). */
    public boolean interactive = false;

    // ---- TEXT ----
    @Nullable public String text;       // plain string or native JSON text component
    public int fontSize = 9;            // vanilla font line height reference
    public int textColor = 0xFFFFFFFF;
    public boolean textShadow = true;
    public String textAlign = "left";   // left|center|right (relative to element box)
    /** Shrink the font so the text always fits the element box (never enlarges). */
    public boolean autoFit = false;

    // ---- IMAGE ----
    @Nullable public String texture;
    public int texW = 256, texH = 256;
    public float u0 = 0, v0 = 0, u1 = 256, v1 = 256;

    // ---- RECT / CIRCLE / TRIANGLE / BORDER / LINE / ROUNDED_RECT / GRADIENT_RECT / ARC ----
    public int fillColor = 0x80000000;
    /** CIRCLE / ARC: ring thickness in local px; 0 = filled disc / pie slice. */
    public float ringThickness = 0f;
    /** TRIANGLE: up|down|left|right. */
    public String pointDirection = "up";
    /** BORDER: outline thickness in local px. */
    public float borderThickness = 1f;
    /** LINE: bar thickness in local px. */
    public float lineThickness = 2f;
    /** ROUNDED_RECT: corner radius in local px. */
    public float cornerRadius = 4f;
    /** GRADIENT_RECT: end color (start = fillColor); direction reuses {@link #direction}. */
    public int fillColor2 = 0x80000000;
    /** ARC: start angle in degrees, clockwise from 12 o'clock. */
    public float arcStart = 0f;
    /** ARC: sweep angle in degrees (clockwise). */
    public float arcSweep = 90f;

    // ---- PROGRESS ----
    public float value = 0, max = 100;
    public int barColor = 0xFF44FF44;
    public int bgColor = 0x80333333;
    public String direction = "horizontal"; // horizontal|vertical
    /** Data-bound sweep time in seconds; 0 = instant. */
    public float progressSmoothSpeed = 0.18f;
    /**
     * PROGRESS value / TEXT content binding:
     * "capture_zone:zone1" (live capture progress),
     * "scoreboard:objective[:holder]" (live score; holder omitted = viewing player), or
     * "entity:<selector>:<field>" (live entity name / health / NBT; see docs).
     */
    @Nullable public String dataSource;

    // ---- STAT ----
    /** Metric id: fps | frame_time | ping | packet_loss | jitter | tps. */
    public String statSource = "fps";
    /** What to show: value | chart | both. */
    public String statDisplay = "both";
    /** Chart history window in samples (clamped to the collector capacity). */
    public int statWindow = 120;

    // ---- TEMPLATE ----
    @Nullable public String templateRef;

    // ---- GROUP ----
    /** Children of a GROUP element; anchored inside this element's box. */
    public final List<HudElement> children = new ArrayList<>();
    private List<HudElement> childRenderOrder = null;

    /** GROUP children sorted by zIndex (stable; rebuilt on demand). */
    public List<HudElement> childRenderOrder() {
        if (childRenderOrder == null) {
            List<HudElement> sorted = new ArrayList<>(children);
            sorted.sort(Comparator.comparingInt(c -> c.zIndex));
            childRenderOrder = sorted;
        }
        return childRenderOrder;
    }

    public void markChildOrderDirty() {
        childRenderOrder = null;
    }

    // ---- GUI interaction (screenType "gui" templates) ----
    /** Optional gate: "scoreboard:obj[:holder] >= 5" etc. Decides onClick vs onClickFail. */
    @Nullable public String condition;
    /** Click behavior when the condition passes (or no condition is set). */
    @Nullable public HudInteraction onClick;
    /** Click behavior when the condition fails. */
    @Nullable public HudInteraction onClickFail;
    /** Clicking this element closes the GUI (fires the template close function). */
    public boolean closeButton = false;

    // ---- 3D projection settings ----
    public boolean hasProjection = false;
    public double worldX, worldY, worldZ;          // static anchor position
    @Nullable public String worldAnchor;            // dynamic, e.g. "capture_zone:zone1"
    public boolean distanceScaleEnabled = false;
    public float distanceRef = 10f;                 // distance where projected scale == 1
    public float distanceMinScale = 0.3f;
    public float distanceMaxScale = 2.0f;
    public boolean throughWalls = true;             // false = fade out when occluded
    /** 目标在屏幕外/相机身后时，把元素钳制到屏幕边缘指示方向（false = 原样移出屏幕/消失）。 */
    public boolean edgeClamp = true;
    /** 钳制范围：距屏幕边缘的内边距（GUI px）。 */
    public float edgeClampPadding = 16f;
    /** 钳制轨道形状：rect（贴边矩形）| ellipse（内切椭圆轨道）。 */
    public String edgeClampShape = "rect";
    /** 钳制时是否额外绘制指向目标方向的箭头。 */
    public boolean edgeArrowEnabled = false;
    /** 箭头尺寸（GUI px）与颜色。 */
    public float edgeArrowSize = 8f;
    public int edgeArrowColor = 0xFFFFFFFF;
    public boolean aimFadeEnabled = false;
    public float aimInnerAngle = 5f;                // fully faded inside this angle (deg)
    public float aimOuterAngle = 15f;               // fade starts at this angle (deg)
    public float aimMinOpacity = 0.15f;

    // ---- Animation ----
    public final List<HudKeyframe> keyframes = new ArrayList<>();
    private HudTrack[] tracks = null;

    /** Compiled per-property tracks; built lazily after parsing/edit. */
    public HudTrack track(int prop) {
        if (tracks == null) compileTracks();
        return tracks[prop];
    }

    public void compileTracks() {
        keyframes.sort(Comparator.comparingInt(k -> k.tick));
        HudTrack[] compiled = new HudTrack[HudKeyframe.PROP_COUNT];
        for (int p = 0; p < HudKeyframe.PROP_COUNT; p++) {
            compiled[p] = HudTrack.compile(keyframes, p);
        }
        tracks = compiled;
    }

    /** Invalidate compiled tracks after editor modifications. */
    public void markTracksDirty() {
        tracks = null;
    }

    public int lastKeyframeTick() {
        int last = 0;
        for (HudKeyframe kf : keyframes) last = Math.max(last, kf.tick);
        for (int i = 0; i < children.size(); i++) {
            last = Math.max(last, children.get(i).lastKeyframeTick());
        }
        return last;
    }

    // ==================== JSON ====================

    public static HudElement fromJson(JsonObject json) {
        HudElement e = new HudElement();
        e.type = Type.fromString(GsonHelper.getAsString(json, "type", "text"));
        e.id = GsonHelper.getAsString(json, "id", "");
        e.anchor = HudAnchor.fromString(GsonHelper.getAsString(json, "anchor", "center"));
        e.origin = HudAnchor.fromString(GsonHelper.getAsString(json, "origin", "center"));
        e.x = GsonHelper.getAsFloat(json, "x", 0);
        e.y = GsonHelper.getAsFloat(json, "y", 0);
        e.w = GsonHelper.getAsFloat(json, "w", 50);
        e.h = GsonHelper.getAsFloat(json, "h", 20);
        e.scale = GsonHelper.getAsFloat(json, "scale", 1f);
        e.rotation = GsonHelper.getAsFloat(json, "rotation", 0f);
        e.opacity = GsonHelper.getAsFloat(json, "opacity", 1f);
        e.zIndex = GsonHelper.getAsInt(json, "zIndex", 0);
        e.visible = GsonHelper.getAsBoolean(json, "visible", true);
        e.interactive = GsonHelper.getAsBoolean(json, "interactive", false);

        switch (e.type) {
            case TEXT:
                e.text = json.has("text") ? textToString(json.get("text")) : null;
                e.fontSize = GsonHelper.getAsInt(json, "fontSize", 9);
                e.textColor = parseColor(json, "color", 0xFFFFFFFF);
                e.textShadow = GsonHelper.getAsBoolean(json, "shadow", true);
                e.textAlign = GsonHelper.getAsString(json, "align", "left");
                e.autoFit = GsonHelper.getAsBoolean(json, "autoFit", false);
                e.dataSource = json.has("dataSource") ? GsonHelper.getAsString(json, "dataSource") : null;
                break;
            case IMAGE:
                e.texture = json.has("texture") ? GsonHelper.getAsString(json, "texture") : null;
                if (json.has("textureSize")) {
                    JsonArray ts = json.getAsJsonArray("textureSize");
                    e.texW = ts.get(0).getAsInt();
                    e.texH = ts.get(1).getAsInt();
                }
                if (json.has("uv")) {
                    JsonArray uv = json.getAsJsonArray("uv");
                    e.u0 = uv.get(0).getAsFloat();
                    e.v0 = uv.get(1).getAsFloat();
                    e.u1 = uv.get(2).getAsFloat();
                    e.v1 = uv.get(3).getAsFloat();
                } else {
                    e.u0 = 0; e.v0 = 0; e.u1 = e.texW; e.v1 = e.texH;
                }
                break;
            case RECT:
                e.fillColor = parseColor(json, "fill", 0x80000000);
                break;
            case CIRCLE:
                e.fillColor = parseColor(json, "fill", 0x80000000);
                e.ringThickness = GsonHelper.getAsFloat(json, "ringThickness", 0f);
                break;
            case TRIANGLE:
                e.fillColor = parseColor(json, "fill", 0x80000000);
                e.pointDirection = GsonHelper.getAsString(json, "pointDirection", "up");
                break;
            case BORDER:
                e.fillColor = parseColor(json, "fill", 0x80000000);
                e.borderThickness = GsonHelper.getAsFloat(json, "borderThickness", 1f);
                break;
            case LINE:
                e.fillColor = parseColor(json, "fill", 0x80000000);
                e.lineThickness = GsonHelper.getAsFloat(json, "lineThickness", 2f);
                break;
            case ROUNDED_RECT:
                e.fillColor = parseColor(json, "fill", 0x80000000);
                e.cornerRadius = GsonHelper.getAsFloat(json, "cornerRadius", 4f);
                break;
            case GRADIENT_RECT:
                e.fillColor = parseColor(json, "fill", 0x80000000);
                e.fillColor2 = parseColor(json, "fill2", 0x80000000);
                e.direction = GsonHelper.getAsString(json, "direction", "horizontal");
                break;
            case ARC:
                e.fillColor = parseColor(json, "fill", 0x80000000);
                e.ringThickness = GsonHelper.getAsFloat(json, "ringThickness", 0f);
                e.arcStart = GsonHelper.getAsFloat(json, "arcStart", 0f);
                e.arcSweep = GsonHelper.getAsFloat(json, "arcSweep", 90f);
                break;
            case PROGRESS:
                e.value = GsonHelper.getAsFloat(json, "value", 0);
                e.max = GsonHelper.getAsFloat(json, "max", 100);
                e.barColor = parseColor(json, "barColor", 0xFF44FF44);
                e.bgColor = parseColor(json, "bgColor", 0x80333333);
                // horizontal | vertical | pie (clockwise mask) | ring (arc band)
                e.direction = GsonHelper.getAsString(json, "direction", "horizontal");
                e.ringThickness = GsonHelper.getAsFloat(json, "ringThickness", 0f);
                e.dataSource = json.has("dataSource") ? GsonHelper.getAsString(json, "dataSource") : null;
                e.progressSmoothSpeed = GsonHelper.getAsFloat(json, "smoothTime",
                        GsonHelper.getAsFloat(json, "smoothSpeed", 0.18f));
                break;
            case TEMPLATE:
                e.templateRef = json.has("template") ? GsonHelper.getAsString(json, "template") : null;
                break;
            case GROUP:
                if (json.has("children")) {
                    for (JsonElement child : json.getAsJsonArray("children")) {
                        e.children.add(fromJson(child.getAsJsonObject()));
                    }
                }
                break;
            case STAT:
                e.statSource = GsonHelper.getAsString(json, "statSource", "fps");
                e.statDisplay = GsonHelper.getAsString(json, "statDisplay", "both");
                e.statWindow = Math.max(2, GsonHelper.getAsInt(json, "statWindow", 120));
                e.fillColor = parseColor(json, "fill", 0xFF4FC3F7);      // chart line
                e.bgColor = parseColor(json, "bgColor", 0x60101418);     // chart backdrop
                e.lineThickness = GsonHelper.getAsFloat(json, "lineThickness", 1.5f);
                e.fontSize = GsonHelper.getAsInt(json, "fontSize", 9);
                e.textColor = parseColor(json, "color", 0xFFFFFFFF);
                e.textShadow = GsonHelper.getAsBoolean(json, "shadow", true);
                e.textAlign = GsonHelper.getAsString(json, "align", "left");
                break;
        }

        e.condition = json.has("condition") ? GsonHelper.getAsString(json, "condition") : null;
        e.onClick = json.has("onClick") && json.get("onClick").isJsonObject()
                ? HudInteraction.fromJson(json.getAsJsonObject("onClick")) : null;
        e.onClickFail = json.has("onClickFail") && json.get("onClickFail").isJsonObject()
                ? HudInteraction.fromJson(json.getAsJsonObject("onClickFail")) : null;
        e.closeButton = GsonHelper.getAsBoolean(json, "closeButton", false);

        if (json.has("projection") && json.get("projection").isJsonObject()) {
            JsonObject proj = json.getAsJsonObject("projection");
            e.hasProjection = true;
            if (proj.has("world") && proj.get("world").isJsonArray()) {
                JsonArray wp = proj.getAsJsonArray("world");
                if (wp.size() >= 3) {
                    e.worldX = wp.get(0).getAsDouble();
                    e.worldY = wp.get(1).getAsDouble();
                    e.worldZ = wp.get(2).getAsDouble();
                }
            }
            e.worldAnchor = proj.has("worldAnchor") ? GsonHelper.getAsString(proj, "worldAnchor") : null;
            if (proj.has("distanceScale") && proj.get("distanceScale").isJsonObject()) {
                JsonObject ds = proj.getAsJsonObject("distanceScale");
                e.distanceScaleEnabled = GsonHelper.getAsBoolean(ds, "enabled", true);
                e.distanceRef = GsonHelper.getAsFloat(ds, "reference", 10f);
                e.distanceMinScale = GsonHelper.getAsFloat(ds, "min", 0.3f);
                e.distanceMaxScale = GsonHelper.getAsFloat(ds, "max", 2.0f);
            }
            e.throughWalls = GsonHelper.getAsBoolean(proj, "throughWalls", true);
            e.edgeClamp = GsonHelper.getAsBoolean(proj, "edgeClamp", true);
            e.edgeClampPadding = GsonHelper.getAsFloat(proj, "edgeClampPadding", 16f);
            e.edgeClampShape = GsonHelper.getAsString(proj, "edgeClampShape", "rect");
            e.edgeArrowEnabled = GsonHelper.getAsBoolean(proj, "edgeArrow", false);
            e.edgeArrowSize = GsonHelper.getAsFloat(proj, "edgeArrowSize", 8f);
            e.edgeArrowColor = parseColor(proj, "edgeArrowColor", 0xFFFFFFFF);
            if (proj.has("aimFade") && proj.get("aimFade").isJsonObject()) {
                JsonObject af = proj.getAsJsonObject("aimFade");
                e.aimFadeEnabled = GsonHelper.getAsBoolean(af, "enabled", true);
                e.aimInnerAngle = GsonHelper.getAsFloat(af, "innerAngle", 5f);
                e.aimOuterAngle = GsonHelper.getAsFloat(af, "outerAngle", 15f);
                e.aimMinOpacity = GsonHelper.getAsFloat(af, "minOpacity", 0.15f);
            }
        }

        if (json.has("keyframes")) {
            for (JsonElement kf : json.getAsJsonArray("keyframes")) {
                e.keyframes.add(HudKeyframe.fromJson(kf.getAsJsonObject()));
            }
        }
        e.compileTracks();
        return e;
    }

    /** TEXT accepts either a plain string or an embedded JSON component object/array. */
    private static String textToString(JsonElement el) {
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type.name().toLowerCase());
        if (!id.isEmpty()) json.addProperty("id", id);
        json.addProperty("anchor", anchor.toJsonName());
        json.addProperty("origin", origin.toJsonName());
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("w", w);
        json.addProperty("h", h);
        if (scale != 1f) json.addProperty("scale", scale);
        if (rotation != 0f) json.addProperty("rotation", rotation);
        if (opacity != 1f) json.addProperty("opacity", opacity);
        if (zIndex != 0) json.addProperty("zIndex", zIndex);
        if (!visible) json.addProperty("visible", false);
        if (interactive) json.addProperty("interactive", true);

        switch (type) {
            case TEXT:
                if (text != null) json.addProperty("text", text);
                if (fontSize != 9) json.addProperty("fontSize", fontSize);
                json.addProperty("color", hexColor(textColor));
                if (!textShadow) json.addProperty("shadow", false);
                if (!"left".equals(textAlign)) json.addProperty("align", textAlign);
                if (autoFit) json.addProperty("autoFit", true);
                if (dataSource != null) json.addProperty("dataSource", dataSource);
                break;
            case IMAGE:
                if (texture != null) json.addProperty("texture", texture);
                JsonArray ts = new JsonArray();
                ts.add(texW); ts.add(texH);
                json.add("textureSize", ts);
                JsonArray uv = new JsonArray();
                uv.add(u0); uv.add(v0); uv.add(u1); uv.add(v1);
                json.add("uv", uv);
                break;
            case RECT:
                json.addProperty("fill", hexColor(fillColor));
                break;
            case CIRCLE:
                json.addProperty("fill", hexColor(fillColor));
                if (ringThickness != 0f) json.addProperty("ringThickness", ringThickness);
                break;
            case TRIANGLE:
                json.addProperty("fill", hexColor(fillColor));
                if (!"up".equals(pointDirection)) json.addProperty("pointDirection", pointDirection);
                break;
            case BORDER:
                json.addProperty("fill", hexColor(fillColor));
                json.addProperty("borderThickness", borderThickness);
                break;
            case LINE:
                json.addProperty("fill", hexColor(fillColor));
                json.addProperty("lineThickness", lineThickness);
                break;
            case ROUNDED_RECT:
                json.addProperty("fill", hexColor(fillColor));
                json.addProperty("cornerRadius", cornerRadius);
                break;
            case GRADIENT_RECT:
                json.addProperty("fill", hexColor(fillColor));
                json.addProperty("fill2", hexColor(fillColor2));
                if (!"horizontal".equals(direction)) json.addProperty("direction", direction);
                break;
            case ARC:
                json.addProperty("fill", hexColor(fillColor));
                if (ringThickness != 0f) json.addProperty("ringThickness", ringThickness);
                json.addProperty("arcStart", arcStart);
                json.addProperty("arcSweep", arcSweep);
                break;
            case PROGRESS:
                json.addProperty("value", value);
                json.addProperty("max", max);
                json.addProperty("barColor", hexColor(barColor));
                json.addProperty("bgColor", hexColor(bgColor));
                if (!"horizontal".equals(direction)) json.addProperty("direction", direction);
                if (ringThickness != 0f) json.addProperty("ringThickness", ringThickness);
                if (dataSource != null) json.addProperty("dataSource", dataSource);
                if (progressSmoothSpeed != 0.18f) json.addProperty("smoothTime", progressSmoothSpeed);
                break;
            case TEMPLATE:
                if (templateRef != null) json.addProperty("template", templateRef);
                break;
            case GROUP:
                JsonArray childArr = new JsonArray();
                for (HudElement child : children) childArr.add(child.toJson());
                json.add("children", childArr);
                break;
            case STAT:
                json.addProperty("statSource", statSource);
                if (!"both".equals(statDisplay)) json.addProperty("statDisplay", statDisplay);
                if (statWindow != 120) json.addProperty("statWindow", statWindow);
                json.addProperty("fill", hexColor(fillColor));
                json.addProperty("bgColor", hexColor(bgColor));
                if (lineThickness != 1.5f) json.addProperty("lineThickness", lineThickness);
                if (fontSize != 9) json.addProperty("fontSize", fontSize);
                json.addProperty("color", hexColor(textColor));
                if (!textShadow) json.addProperty("shadow", false);
                if (!"left".equals(textAlign)) json.addProperty("align", textAlign);
                break;
        }

        if (condition != null) json.addProperty("condition", condition);
        if (onClick != null) json.add("onClick", onClick.toJson());
        if (onClickFail != null) json.add("onClickFail", onClickFail.toJson());
        if (closeButton) json.addProperty("closeButton", true);

        if (hasProjection) {
            JsonObject proj = new JsonObject();
            JsonArray wp = new JsonArray();
            wp.add(worldX); wp.add(worldY); wp.add(worldZ);
            proj.add("world", wp);
            if (worldAnchor != null) {
                proj.addProperty("worldAnchor", worldAnchor);
            }
            if (distanceScaleEnabled) {
                JsonObject ds = new JsonObject();
                ds.addProperty("enabled", true);
                ds.addProperty("reference", distanceRef);
                ds.addProperty("min", distanceMinScale);
                ds.addProperty("max", distanceMaxScale);
                proj.add("distanceScale", ds);
            }
            if (!throughWalls) proj.addProperty("throughWalls", false);
            if (!edgeClamp) proj.addProperty("edgeClamp", false);
            if (edgeClampPadding != 16f) proj.addProperty("edgeClampPadding", edgeClampPadding);
            if (!"rect".equals(edgeClampShape)) proj.addProperty("edgeClampShape", edgeClampShape);
            if (edgeArrowEnabled) {
                proj.addProperty("edgeArrow", true);
                if (edgeArrowSize != 8f) proj.addProperty("edgeArrowSize", edgeArrowSize);
                proj.addProperty("edgeArrowColor", hexColor(edgeArrowColor));
            }
            if (aimFadeEnabled) {
                JsonObject af = new JsonObject();
                af.addProperty("enabled", true);
                af.addProperty("innerAngle", aimInnerAngle);
                af.addProperty("outerAngle", aimOuterAngle);
                af.addProperty("minOpacity", aimMinOpacity);
                proj.add("aimFade", af);
            }
            json.add("projection", proj);
        }

        if (!keyframes.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (HudKeyframe kf : keyframes) arr.add(kf.toJson());
            json.add("keyframes", arr);
        }
        return json;
    }

    // ==================== Color helpers ====================

    public static int parseColor(JsonObject json, String key, int defaultVal) {
        if (!json.has(key)) return defaultVal;
        JsonElement el = json.get(key);
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString().replace("#", "").replace("0x", "");
            try {
                long v = Long.parseLong(s, 16);
                if (s.length() <= 6) v |= 0xFF000000L; // RGB shorthand -> opaque
                return (int) v;
            } catch (NumberFormatException e) {
                return defaultVal;
            }
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            float r = arr.get(0).getAsFloat();
            float g = arr.get(1).getAsFloat();
            float b = arr.get(2).getAsFloat();
            float a = arr.size() > 3 ? arr.get(3).getAsFloat() : 1f;
            return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
        }
        return defaultVal;
    }

    public static String hexColor(int argb) {
        return String.format("#%08X", argb);
    }

    public HudElement copy() {
        return fromJson(toJson());
    }
}
