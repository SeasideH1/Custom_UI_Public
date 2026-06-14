package com.lootmatrix.customui.client.hud;

import com.lootmatrix.customui.client.render.RenderResourceCache;
import com.lootmatrix.customui.hud.HudElement;
import com.lootmatrix.customui.hud.HudTemplate;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-element client-side runtime state, kept out of the common data model.
 * Caches parsed text components, measured widths, resolved texture locations
 * and throttled occlusion results so the render hot path stays allocation-free.
 */
@OnlyIn(Dist.CLIENT)
public final class HudElementRuntime {

    private static final Map<HudElement, HudElementRuntime> STATES = new IdentityHashMap<>();

    // Parsed text cache (signature = raw text string identity + content)
    @Nullable private Component parsedText;
    @Nullable private String parsedTextSource;
    private int cachedTextWidth = -1;

    // Lazy texture resolution
    @Nullable private ResourceLocation textureRl;
    @Nullable private String textureSource;

    // Parsed "capture_zone:<id>" bindings (dataSource / worldAnchor)
    @Nullable private String dataSourceSource;
    @Nullable private String dataSourceZoneId;
    @Nullable private String worldAnchorSource;
    @Nullable private String worldAnchorZoneId;

    // Parsed "scoreboard:obj[:holder]" binding (dataSource) + value-keyed text cache
    @Nullable private String scoreboardSource;
    @Nullable private String scoreboardKey;

    // Parsed "entity:<selector>:<field>" bindings
    @Nullable private String entitySource;
    @Nullable private String entityKey;

    @Nullable private Component boundText;
    @Nullable private String boundTextSource;
    private int boundTextValue = Integer.MIN_VALUE;
    private int boundTextWidth = -1;

    @Nullable private Component boundEntityText;
    @Nullable private String boundEntityTextSource;
    @Nullable private String boundEntityResolvedValue;
    private int boundEntityTextWidth = -1;

    // Resolved TEMPLATE reference (avoids per-frame registry scans for short ids)
    @Nullable private String templateRefSource;
    @Nullable private HudTemplate resolvedTemplateRef;
    private boolean templateRefResolved = false;
    private int templateRefGeneration = Integer.MIN_VALUE;

    // Throttled occlusion check (for throughWalls=false projection)
    float occlusionAlpha = 1f;      // smoothed visibility 0..1
    long lastOcclusionCheckTick = Long.MIN_VALUE;
    boolean lastOcclusionBlocked = false;

    // Smoothed display fraction for data-bound PROGRESS (avoids integer-step jumps)
    private float smoothedFraction = Float.NaN;
    private float smoothStartFraction = Float.NaN;
    private float smoothTargetFraction = Float.NaN;
    private float smoothElapsedSeconds = 0f;
    private long smoothLastNanos = 0L;

    // STAT: resolved metric source id + value-keyed readout text cache
    @Nullable private String statSourceSource;
    private int statSourceId = -1;
    @Nullable private Component statText;
    private int statTextKey = Integer.MIN_VALUE;
    private int statTextWidth = -1;

    /**
     * Time-based interpolation toward the bound target fraction, so scoreboard-driven
     * bars animate over the configured PROGRESS 平滑时间 instead of stepping.
     */
    public float smoothedFraction(float target, float smoothTimeSeconds) {
        long now = System.nanoTime();
        float elapsedSeconds = 0f;
        if (smoothLastNanos != 0L) {
            elapsedSeconds = Math.min(0.25f, Math.max(0f, (now - smoothLastNanos) / 1_000_000_000f));
        }
        smoothLastNanos = now;

        if (Float.isNaN(smoothedFraction) || smoothTimeSeconds <= 0f) {
            smoothedFraction = target;
            smoothStartFraction = target;
            smoothTargetFraction = target;
            smoothElapsedSeconds = 0f;
        } else {
            if (Float.isNaN(smoothTargetFraction)
                    || Math.abs(target - smoothTargetFraction) > 0.0005f) {
                smoothStartFraction = smoothedFraction;
                smoothTargetFraction = target;
                smoothElapsedSeconds = 0f;
            }
            smoothElapsedSeconds += elapsedSeconds;
            smoothedFraction = smoothFractionForElapsed(
                    smoothStartFraction, smoothTargetFraction, smoothElapsedSeconds, smoothTimeSeconds);
            if (Math.abs(smoothTargetFraction - smoothedFraction) < 0.0015f) {
                smoothedFraction = smoothTargetFraction;
            }
        }
        return smoothedFraction;
    }

    static float smoothFractionForElapsed(float start, float target,
                                          float elapsedSeconds, float durationSeconds) {
        if (durationSeconds <= 0f) {
            return target;
        }
        float t = Math.max(0f, Math.min(1f, elapsedSeconds / durationSeconds));
        return start + (target - start) * t;
    }

    public static HudElementRuntime of(HudElement element) {
        HudElementRuntime state = STATES.get(element);
        if (state == null) {
            state = new HudElementRuntime();
            STATES.put(element, state);
        }
        return state;
    }

    public static void clearAll() {
        STATES.clear();
    }

    /** Drop runtime states of a replaced template definition only. */
    public static void clearTemplate(HudTemplate template) {
        for (HudElement element : template.elements) {
            removeRecursive(element);
        }
    }

    private static void removeRecursive(HudElement element) {
        STATES.remove(element);
        for (int i = 0; i < element.children.size(); i++) {
            removeRecursive(element.children.get(i));
        }
    }

    /** Parse plain text or a native JSON text component (cached until the source changes). */
    @Nullable
    public Component textComponent(HudElement element) {
        String src = element.text;
        if (src == null || src.isEmpty()) return null;
        // Identity check first (common case), then equality for editor live edits
        if (parsedText != null && (parsedTextSource == src || src.equals(parsedTextSource))) {
            return parsedText;
        }
        Component parsed;
        String trimmed = src.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            try {
                parsed = Component.Serializer.fromJson(trimmed);
                if (parsed == null) parsed = Component.literal(src);
            } catch (Exception e) {
                parsed = Component.literal(src);
            }
        } else {
            parsed = Component.literal(src);
        }
        parsedText = parsed;
        parsedTextSource = src;
        cachedTextWidth = -1;
        return parsed;
    }

    /** Text width measured once per parsed component. */
    public int textWidth(HudElement element) {
        Component component = textComponent(element);
        if (component == null) return 0;
        if (cachedTextWidth < 0) {
            cachedTextWidth = Minecraft.getInstance().font.width(component);
        }
        return cachedTextWidth;
    }

    /** Lazily resolved texture; never parsed on the hot path more than once. */
    @Nullable
    public ResourceLocation texture(HudElement element) {
        String src = element.texture;
        if (src == null || src.isEmpty()) return null;
        if (textureRl != null && (textureSource == src || src.equals(textureSource))) {
            return textureRl;
        }
        textureRl = RenderResourceCache.get(src);
        textureSource = src;
        return textureRl;
    }

    /**
     * Embedded template referenced by a TEMPLATE element, resolved once per
     * (source string, cache generation) pair. Negative lookups are cached too,
     * so a missing reference never re-scans the registry every frame.
     */
    @Nullable
    public HudTemplate resolvedTemplate(HudElement element) {
        String src = element.templateRef;
        if (src == null || src.isEmpty()) return null;
        int gen = HudClientTemplateCache.generation();
        if (templateRefResolved && templateRefGeneration == gen
                && (templateRefSource == src || src.equals(templateRefSource))) {
            return resolvedTemplateRef;
        }
        resolvedTemplateRef = HudClientTemplateCache.get(src);
        templateRefSource = src;
        templateRefGeneration = gen;
        templateRefResolved = true;
        return resolvedTemplateRef;
    }

    private static final String CAPTURE_ZONE_PREFIX = "capture_zone:";

    /** Capture-zone id bound as PROGRESS data source, or null. Parsed once per source string. */
    @Nullable
    public String captureZoneDataSource(HudElement element) {
        String src = element.dataSource;
        if (src == null || src.isEmpty()) return null;
        if (dataSourceSource == src || src.equals(dataSourceSource)) {
            return dataSourceZoneId;
        }
        dataSourceSource = src;
        dataSourceZoneId = src.startsWith(CAPTURE_ZONE_PREFIX)
                ? src.substring(CAPTURE_ZONE_PREFIX.length()) : null;
        return dataSourceZoneId;
    }

    /** Scoreboard binding key ("objective\u0000holder") or null. Parsed once per source string. */
    @Nullable
    public String scoreboardKey(HudElement element) {
        String src = element.dataSource;
        if (src == null || src.isEmpty()) return null;
        if (scoreboardSource == src || src.equals(scoreboardSource)) {
            return scoreboardKey;
        }
        scoreboardSource = src;
        com.lootmatrix.customui.hud.HudScoreboardBinding binding =
                com.lootmatrix.customui.hud.HudScoreboardBinding.parse(src);
        scoreboardKey = binding != null ? binding.key() : null;
        return scoreboardKey;
    }

    /** Entity binding key ({@code selector\u0000field}) or null. Parsed once per source string. */
    @Nullable
    public String entityKey(HudElement element) {
        String src = element.dataSource;
        if (src == null || src.isEmpty()) return null;
        if (entitySource == src || src.equals(entitySource)) {
            return entityKey;
        }
        entitySource = src;
        com.lootmatrix.customui.hud.HudEntityBinding binding =
                com.lootmatrix.customui.hud.HudEntityBinding.parse(src);
        entityKey = binding != null ? binding.key() : null;
        return entityKey;
    }

    @Nullable
    public com.lootmatrix.customui.hud.HudEntityBinding entityBinding(HudElement element) {
        String src = element.dataSource;
        return src == null ? null : com.lootmatrix.customui.hud.HudEntityBinding.parse(src);
    }

    /**
     * TEXT bound to an entity field: {@code {value}} placeholders are replaced by
     * the resolved string (empty text shows the raw value).
     */
    @Nullable
    public Component boundEntityTextComponent(HudElement element, String value) {
        String src = element.text;
        if (boundEntityText != null && value.equals(boundEntityResolvedValue)
                && (boundEntityTextSource == src || (src != null && src.equals(boundEntityTextSource)))) {
            return boundEntityText;
        }
        String resolved;
        if (src == null || src.isEmpty()) {
            resolved = value;
        } else {
            resolved = src.replace("{value}", value);
        }
        Component parsed;
        String trimmed = resolved.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            try {
                parsed = Component.Serializer.fromJson(trimmed);
                if (parsed == null) parsed = Component.literal(resolved);
            } catch (Exception e) {
                parsed = Component.literal(resolved);
            }
        } else {
            parsed = Component.literal(resolved);
        }
        boundEntityText = parsed;
        boundEntityTextSource = src;
        boundEntityResolvedValue = value;
        boundEntityTextWidth = -1;
        return parsed;
    }

    public int boundEntityTextWidth(HudElement element, String value) {
        Component component = boundEntityTextComponent(element, value);
        if (component == null) return 0;
        if (boundEntityTextWidth < 0) {
            boundEntityTextWidth = Minecraft.getInstance().font.width(component);
        }
        return boundEntityTextWidth;
    }

    /**
     * TEXT bound to a scoreboard value: "{value}" placeholders are replaced by
     * the score (empty text shows the bare number). Re-parsed only when the
     * source string or the score changes.
     */
    @Nullable
    public Component boundTextComponent(HudElement element, int value) {
        String src = element.text;
        if (boundText != null && boundTextValue == value
                && (boundTextSource == src || (src != null && src.equals(boundTextSource)))) {
            return boundText;
        }
        String resolved;
        if (src == null || src.isEmpty()) {
            resolved = Integer.toString(value);
        } else {
            resolved = src.replace("{value}", Integer.toString(value));
        }
        Component parsed;
        String trimmed = resolved.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            try {
                parsed = Component.Serializer.fromJson(trimmed);
                if (parsed == null) parsed = Component.literal(resolved);
            } catch (Exception e) {
                parsed = Component.literal(resolved);
            }
        } else {
            parsed = Component.literal(resolved);
        }
        boundText = parsed;
        boundTextSource = src;
        boundTextValue = value;
        boundTextWidth = -1;
        return parsed;
    }

    /** Width of the bound text component (recomputed when the value changes). */
    public int boundTextWidth(HudElement element, int value) {
        Component component = boundTextComponent(element, value);
        if (component == null) return 0;
        if (boundTextWidth < 0) {
            boundTextWidth = Minecraft.getInstance().font.width(component);
        }
        return boundTextWidth;
    }

    /** Capture-zone id bound as dynamic 3D world anchor, or null. Parsed once per source string. */
    @Nullable
    public String captureZoneWorldAnchor(HudElement element) {
        String src = element.worldAnchor;
        if (src == null || src.isEmpty()) return null;
        if (worldAnchorSource == src || src.equals(worldAnchorSource)) {
            return worldAnchorZoneId;
        }
        worldAnchorSource = src;
        worldAnchorZoneId = src.startsWith(CAPTURE_ZONE_PREFIX)
                ? src.substring(CAPTURE_ZONE_PREFIX.length()) : null;
        return worldAnchorZoneId;
    }

    // ==================== STAT metric bindings ====================

    /** Resolved {@code ClientMetrics} source id (parsed once per source string). */
    public int statSourceId(HudElement element) {
        String src = element.statSource;
        if (statSourceId >= 0 && (statSourceSource == src
                || (src != null && src.equals(statSourceSource)))) {
            return statSourceId;
        }
        statSourceSource = src;
        statSourceId = com.lootmatrix.customui.client.metrics.ClientMetrics.sourceId(src);
        return statSourceId;
    }

    /**
     * Formatted metric readout ("62 fps", "16.4 ms", "1.2%", "19.8 tps"),
     * rebuilt only when the displayed (0.1-quantized) value changes.
     */
    public Component statValueComponent(int source, float value) {
        int key = (source << 24) ^ (int) (value * 10f);
        if (statText != null && statTextKey == key) {
            return statText;
        }
        statTextKey = key;
        statTextWidth = -1;
        String formatted = switch (source) {
            case com.lootmatrix.customui.client.metrics.ClientMetrics.SRC_FPS ->
                    Math.round(value) + " fps";
            case com.lootmatrix.customui.client.metrics.ClientMetrics.SRC_FRAME_TIME,
                    com.lootmatrix.customui.client.metrics.ClientMetrics.SRC_PING,
                    com.lootmatrix.customui.client.metrics.ClientMetrics.SRC_JITTER ->
                    oneDecimal(value) + " ms";
            case com.lootmatrix.customui.client.metrics.ClientMetrics.SRC_PACKET_LOSS ->
                    oneDecimal(value) + "%";
            case com.lootmatrix.customui.client.metrics.ClientMetrics.SRC_TPS ->
                    oneDecimal(value) + " tps";
            default -> oneDecimal(value);
        };
        statText = Component.literal(formatted);
        return statText;
    }

    public int statValueWidth(int source, float value) {
        Component component = statValueComponent(source, value);
        if (statTextWidth < 0) {
            statTextWidth = Minecraft.getInstance().font.width(component);
        }
        return statTextWidth;
    }

    /** One-decimal formatting without String.format (no Object[] allocation). */
    private static String oneDecimal(float value) {
        int scaled = Math.round(value * 10f);
        int whole = scaled / 10;
        int frac = Math.abs(scaled % 10);
        return whole + "." + frac;
    }

    /** Invalidate caches after editor-side mutation of this element. */
    public void invalidate() {
        parsedText = null;
        parsedTextSource = null;
        cachedTextWidth = -1;
        textureRl = null;
        textureSource = null;
        dataSourceSource = null;
        dataSourceZoneId = null;
        worldAnchorSource = null;
        worldAnchorZoneId = null;
        scoreboardSource = null;
        scoreboardKey = null;
        entitySource = null;
        entityKey = null;
        boundText = null;
        boundTextSource = null;
        boundTextValue = Integer.MIN_VALUE;
        boundTextWidth = -1;
        boundEntityText = null;
        boundEntityTextSource = null;
        boundEntityResolvedValue = null;
        boundEntityTextWidth = -1;
        templateRefSource = null;
        resolvedTemplateRef = null;
        templateRefResolved = false;
        smoothedFraction = Float.NaN;
        smoothStartFraction = Float.NaN;
        smoothTargetFraction = Float.NaN;
        smoothElapsedSeconds = 0f;
        smoothLastNanos = 0L;
        statSourceSource = null;
        statSourceId = -1;
        statText = null;
        statTextKey = Integer.MIN_VALUE;
        statTextWidth = -1;
    }

    public static void invalidate(HudElement element) {
        HudElementRuntime state = STATES.get(element);
        if (state != null) state.invalidate();
    }
}
