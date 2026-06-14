package com.lootmatrix.customui.client.hud;

import com.lootmatrix.customui.client.AlphaFadeHelper;
import com.lootmatrix.customui.client.CaptureZoneBoundaryRenderer;
import com.lootmatrix.customui.client.CaptureZoneHudRenderer;
import com.lootmatrix.customui.client.render.WorldToScreenUtil;
import com.lootmatrix.customui.hud.HudElement;
import com.lootmatrix.customui.hud.HudKeyframe;
import com.lootmatrix.customui.hud.HudTemplate;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Single overlay that renders every active HUD template instance.
 *
 * Rendering is collected into a static draw-entry buffer first, then flushed in
 * three batched phases (colored geometry → textured quads grouped by texture →
 * text via {@code drawInBatch} with one {@code endBatch}) so the whole HUD
 * system costs a handful of draw calls per frame regardless of element count.
 *
 * Per-element transforms (anchor/origin, offset, rotation, scale — animated via
 * keyframe tracks) are folded into a 2D similarity (a, b, tx, ty) and applied
 * per-vertex inline, so nesting and rotation never allocate matrices.
 */
@OnlyIn(Dist.CLIENT)
public final class HudTemplateOverlayRenderer implements IGuiOverlay {

    private static final HudTemplateOverlayRenderer INSTANCE = new HudTemplateOverlayRenderer();

    public static HudTemplateOverlayRenderer getInstance() {
        return INSTANCE;
    }

    private HudTemplateOverlayRenderer() {}

    private static final int FULL_BRIGHT = 15728880;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    /** Occlusion raycasts (throughWalls=false) are throttled to once per N ticks. */
    private static final int OCCLUSION_INTERVAL_TICKS = 3;
    /** Screen-edge padding (GUI px) when pinning off-screen projected elements. */
    private static final float EDGE_CLAMP_PADDING = 16f;

    private static final int KIND_RECT = 0;
    private static final int KIND_PROGRESS = 1;
    private static final int KIND_IMAGE = 2;
    private static final int KIND_TEXT = 3;
    private static final int KIND_CIRCLE = 4;
    private static final int KIND_TRIANGLE = 5;
    private static final int KIND_BORDER = 6;
    private static final int KIND_ROUNDED_RECT = 7;
    private static final int KIND_GRADIENT = 8;
    private static final int KIND_ARC = 9;
    private static final int KIND_CHART = 10;

    /** Mutable pooled draw entry (one per visible leaf element per frame). */
    private static final class DrawEntry {
        int kind;
        // combined similarity transform: x' = a*x - b*y + tx, y' = b*x + a*y + ty
        float a, b, tx, ty;
        // unrotated local box
        float left, top, width, height;
        int color1;          // rect/shape fill / progress bar / gradient start / text color
        int color2;          // progress background / gradient end
        float fraction;      // progress 0..1
        boolean vertical;    // progress / gradient direction
        float shapeParam;    // circle+arc ring / border thickness / corner radius (local px)
        float shapeParam2;   // arc start angle (deg)
        float shapeParam3;   // arc sweep angle (deg)
        int shapeDir;        // triangle direction: 0=up 1=down 2=left 3=right
        @Nullable ResourceLocation texture;
        float u0, v0, u1, v1;
        @Nullable Component text;
        float textX, textY;  // local text position (already aligned)
        float fontScale;
        boolean shadow;

        void release() {
            texture = null;
            text = null;
        }
    }

    private static final List<DrawEntry> ENTRIES = new ArrayList<>();
    private static int entryCount = 0;
    private static final Matrix4f TEXT_MATRIX = new Matrix4f();
    /** Scratch for allocation-free world→screen projection: {x, y, w}. */
    private static final double[] PROJECTED = new double[3];

    private static DrawEntry nextEntry() {
        if (entryCount == ENTRIES.size()) {
            ENTRIES.add(new DrawEntry());
        }
        return ENTRIES.get(entryCount++);
    }

    // ==================== Overlay entry point ====================

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        List<HudPlaybackManager.HudInstance> instances = HudPlaybackManager.instances();
        if (instances.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        entryCount = 0;
        float partial = mc.isPaused() ? 0f : partialTick;
        for (int i = 0; i < instances.size(); i++) {
            HudPlaybackManager.HudInstance instance = instances.get(i);
            HudTemplate template = instance.template;
            if (template == null) continue;
            collectTemplate(template, instance.elapsedTicks + partial,
                    0f, 0f, screenWidth, screenHeight,
                    1f, 0f, 0f, 0f, 1f, 0, false);
        }
        if (entryCount > 0) {
            drawCollected(graphics);
        }
    }

    /** Editor canvas preview: identical pipeline, 3D projection flattened to 2D. */
    public static void renderTemplatePreview(GuiGraphics graphics, HudTemplate template,
                                             float time, int screenWidth, int screenHeight) {
        entryCount = 0;
        collectTemplate(template, time, 0f, 0f, screenWidth, screenHeight,
                1f, 0f, 0f, 0f, 1f, 0, true);
        if (entryCount > 0) {
            drawCollected(graphics);
        }
    }

    /**
     * Editor helper: unrotated screen-space box of a top-level element at a time
     * (scale applied around the origin pivot, rotation/3D ignored).
     * Writes {left, top, width, height} into {@code out}.
     */
    public static void resolveLocalBox(HudElement e, float time, int screenWidth, int screenHeight, float[] out) {
        resolveLocalBoxInRect(e, time, 0f, 0f, screenWidth, screenHeight, out);
    }

    /**
     * Same as {@link #resolveLocalBox} but anchored inside an arbitrary rect
     * (a GROUP's resolved box, for editing/hit-testing nested children).
     */
    public static void resolveLocalBoxInRect(HudElement e, float time,
                                             float rectX, float rectY, float rectW, float rectH, float[] out) {
        float ex = e.track(HudKeyframe.PROP_X).evaluate(time, e.x);
        float ey = e.track(HudKeyframe.PROP_Y).evaluate(time, e.y);
        float scale = e.track(HudKeyframe.PROP_SCALE).evaluate(time, e.scale);
        float pivotX = rectX + e.anchor.fx * rectW + ex;
        float pivotY = rectY + e.anchor.fy * rectH + ey;
        float w = e.w * scale;
        float h = e.h * scale;
        out[0] = pivotX - e.origin.fx * w;
        out[1] = pivotY - e.origin.fy * h;
        out[2] = w;
        out[3] = h;
    }

    /**
     * GUI screens: optional per-element local time rebase (click-triggered
     * keyframe replays). Set before rendering, cleared after; identity-keyed.
     */
    @Nullable private static java.util.Map<HudElement, Float> timeOverrides = null;

    public static void setTimeOverrides(@Nullable java.util.Map<HudElement, Float> overrides) {
        timeOverrides = overrides;
    }

    // ==================== Collection ====================

    /**
     * Evaluate all elements of a template into draw entries.
     *
     * @param rectX/rectY/rectW/rectH anchor space (screen, or parent element box for nesting)
     * @param pa/pb/ptx/pty           accumulated parent similarity transform
     * @param flatten2D               editor preview: ignore 3D projection
     */
    private static void collectTemplate(HudTemplate template, float time,
                                        float rectX, float rectY, float rectW, float rectH,
                                        float pa, float pb, float ptx, float pty,
                                        float alphaMul, int depth, boolean flatten2D) {
        collectElements(template.renderOrder(), time, rectX, rectY, rectW, rectH,
                pa, pb, ptx, pty, alphaMul, depth, flatten2D);
    }

    private static void collectElements(List<HudElement> order, float timeIn,
                                        float rectX, float rectY, float rectW, float rectH,
                                        float pa, float pb, float ptx, float pty,
                                        float alphaMul, int depth, boolean flatten2D) {
        for (int i = 0; i < order.size(); i++) {
            HudElement e = order.get(i);
            if (!e.visible) continue;

            // Optional click-animation rebase (GUI screens) for this element subtree
            float time = timeIn;
            if (timeOverrides != null) {
                Float rebase = timeOverrides.get(e);
                if (rebase != null) time = Math.max(0f, timeIn - rebase);
            }

            float ex = e.track(HudKeyframe.PROP_X).evaluate(time, e.x);
            float ey = e.track(HudKeyframe.PROP_Y).evaluate(time, e.y);
            float scale = e.track(HudKeyframe.PROP_SCALE).evaluate(time, e.scale);
            float rotation = e.track(HudKeyframe.PROP_ROTATION).evaluate(time, e.rotation);
            float alpha = e.track(HudKeyframe.PROP_OPACITY).evaluate(time, e.opacity) * alphaMul;

            float flash = e.track(HudKeyframe.PROP_FLASH).evaluate(time, 0f);
            if (flash > 0f) {
                float flashMin = clamp01(e.track(HudKeyframe.PROP_FLASH_MIN).evaluate(time, 0f));
                float osc = 0.5f + 0.5f * (float) Math.sin(TWO_PI * flash * (time / 20f));
                alpha *= flashMin + (1f - flashMin) * osc;
            }

            // Early cull: projection/raycast work below can only lower alpha further,
            // so fully faded elements (intro/outro tails) skip it entirely.
            if (AlphaFadeHelper.shouldSkipRender(alpha)) continue;

            float pivotX = rectX + e.anchor.fx * rectW + ex;
            float pivotY = rectY + e.anchor.fy * rectH + ey;
            float scale3 = 1f;

            // ---- 3D projection (top-level elements only) ----
            if (!flatten2D && depth == 0 && e.hasProjection) {
                float spaceT = clamp01(e.track(HudKeyframe.PROP_SPACE).evaluate(time, 1f));
                if (spaceT > 0f) {
                    HudElementRuntime rt = HudElementRuntime.of(e);
                    double wx = e.track(HudKeyframe.PROP_WORLD_X).evaluate(time, (float) e.worldX);
                    double wy = e.track(HudKeyframe.PROP_WORLD_Y).evaluate(time, (float) e.worldY);
                    double wz = e.track(HudKeyframe.PROP_WORLD_Z).evaluate(time, (float) e.worldZ);
                    String anchorZone = rt.captureZoneWorldAnchor(e);
                    if (anchorZone != null) {
                        Vec3 zoneCenter = CaptureZoneBoundaryRenderer.getInstance().getZoneWorldCenter(anchorZone);
                        if (zoneCenter != null) {
                            wx = zoneCenter.x;
                            wy = zoneCenter.y;
                            wz = zoneCenter.z;
                        }
                    }

                    WorldToScreenUtil.worldToScreen(wx, wy, wz, PROJECTED);
                    boolean behind = PROJECTED[2] <= 0;
                    if (behind && !e.edgeClamp) {
                        if (spaceT >= 0.999f) continue; // fully 3D and behind the camera
                        // partially blended: keep the 2D position and fade with the 3D weight
                        alpha *= 1f - spaceT;
                        if (AlphaFadeHelper.shouldSkipRender(alpha)) continue;
                    } else {
                        // Behind the camera the perspective divide mirrors the point; flip it back
                        float px = behind ? rectW - (float) PROJECTED[0] : (float) PROJECTED[0];
                        float py = behind ? rectH - (float) PROJECTED[1] : (float) PROJECTED[1];
                        if (e.edgeClamp && (behind || px < 0 || px > rectW || py < 0 || py > rectH)) {
                            // Off-screen: pin to the configured clamp track along the
                            // ray from screen center (rect edge / inscribed ellipse / circle)
                            float ecx = rectW * 0.5f, ecy = rectH * 0.5f;
                            float dirX = px - ecx, dirY = py - ecy;
                            float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
                            if (len < 0.001f) { dirX = 0f; dirY = -1f; } else { dirX /= len; dirY /= len; }
                            float pad = Math.max(0f, e.edgeClampPadding);
                            float maxDx = Math.max(4f, ecx - pad);
                            float maxDy = Math.max(4f, ecy - pad);
                            float et;
                            if ("ellipse".equals(e.edgeClampShape) || "circle".equals(e.edgeClampShape)) {
                                if ("circle".equals(e.edgeClampShape)) {
                                    maxDx = maxDy = Math.min(maxDx, maxDy);
                                }
                                et = 1f / (float) Math.sqrt(
                                        (dirX * dirX) / (maxDx * maxDx) + (dirY * dirY) / (maxDy * maxDy));
                            } else {
                                et = Math.min(
                                        Math.abs(dirX) > 0.001f ? maxDx / Math.abs(dirX) : Float.MAX_VALUE,
                                        Math.abs(dirY) > 0.001f ? maxDy / Math.abs(dirY) : Float.MAX_VALUE);
                            }
                            px = ecx + dirX * et;
                            py = ecy + dirY * et;
                            if (e.edgeArrowEnabled) {
                                emitEdgeArrow(e, px, py, dirX, dirY, scale, alpha);
                            }
                        } else if (!e.edgeClamp) {
                            // Clamp near-frustum-edge blowups to sane offscreen coordinates
                            px = Math.max(-rectW, Math.min(rectW * 2f, px));
                            py = Math.max(-rectH, Math.min(rectH * 2f, py));
                        }
                        pivotX = pivotX + (px - pivotX) * spaceT;
                        pivotY = pivotY + (py - pivotY) * spaceT;

                        Minecraft mc = Minecraft.getInstance();
                        Camera camera = mc.gameRenderer.getMainCamera();
                        Vec3 camPos = camera.getPosition();
                        double dx = wx - camPos.x;
                        double dy = wy - camPos.y;
                        double dz = wz - camPos.z;
                        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                        if (e.distanceScaleEnabled) {
                            float f = (float) (e.distanceRef / Math.max(0.01, dist));
                            f = Math.max(e.distanceMinScale, Math.min(e.distanceMaxScale, f));
                            scale3 = 1f + (f - 1f) * spaceT;
                        }
                        if (!e.throughWalls && mc.level != null) {
                            alpha *= 1f + (occlusionAlpha(rt, mc, camPos, wx, wy, wz) - 1f) * spaceT;
                        }
                        if (e.aimFadeEnabled && dist > 0.01) {
                            Vector3f look = camera.getLookVector();
                            double dot = (dx * look.x + dy * look.y + dz * look.z) / dist;
                            dot = Math.max(-1.0, Math.min(1.0, dot));
                            float angle = (float) Math.toDegrees(Math.acos(dot));
                            float fade;
                            if (angle <= e.aimInnerAngle) {
                                fade = e.aimMinOpacity;
                            } else if (angle >= e.aimOuterAngle) {
                                fade = 1f;
                            } else {
                                float t = (angle - e.aimInnerAngle) / Math.max(0.01f, e.aimOuterAngle - e.aimInnerAngle);
                                fade = e.aimMinOpacity + (1f - e.aimMinOpacity) * t;
                            }
                            alpha *= 1f + (fade - 1f) * spaceT;
                        }
                    }
                }
            }

            if (AlphaFadeHelper.shouldSkipRender(alpha)) continue;

            // ---- local similarity (scale+rotation around the pivot) composed with parent ----
            float es = scale * scale3;
            float la, lb;
            if (rotation != 0f) {
                float rad = rotation * DEG_TO_RAD;
                la = (float) Math.cos(rad) * es;
                lb = (float) Math.sin(rad) * es;
            } else {
                la = es;
                lb = 0f;
            }
            float ltx = pivotX - (la * pivotX - lb * pivotY);
            float lty = pivotY - (lb * pivotX + la * pivotY);
            float ca = pa * la - pb * lb;
            float cb = pb * la + pa * lb;
            float ctx = pa * ltx - pb * lty + ptx;
            float cty = pb * ltx + pa * lty + pty;

            float left = pivotX - e.origin.fx * e.w;
            float top = pivotY - e.origin.fy * e.h;

            switch (e.type) {
                case TEMPLATE -> {
                    if (depth >= HudTemplate.MAX_NESTING_DEPTH || e.templateRef == null) break;
                    HudTemplate child = HudElementRuntime.of(e).resolvedTemplate(e);
                    if (child == null) break;
                    float childTime = time;
                    if (child.loop && !child.isPersistent()) {
                        int lifetime = child.effectiveLifetime();
                        if (lifetime > 0) childTime = time % lifetime;
                    }
                    collectTemplate(child, childTime, left, top, e.w, e.h,
                            ca, cb, ctx, cty, alpha, depth + 1, flatten2D);
                }
                case GROUP -> {
                    if (depth >= HudTemplate.MAX_NESTING_DEPTH || e.children.isEmpty()) break;
                    // Children share this group's transform/opacity/projection;
                    // they keep their 2D layout when the group is projected to 3D.
                    collectElements(e.childRenderOrder(), time, left, top, e.w, e.h,
                            ca, cb, ctx, cty, alpha, depth + 1, flatten2D);
                }
                case STAT -> {
                    HudElementRuntime rt = HudElementRuntime.of(e);
                    int source = rt.statSourceId(e);
                    boolean showChart = !"value".equals(e.statDisplay);
                    boolean showValue = !"chart".equals(e.statDisplay);
                    if (showChart) {
                        if (((e.bgColor >>> 24) & 0xFF) != 0) {
                            DrawEntry bg = nextEntry();
                            bg.kind = KIND_RECT;
                            bg.a = ca; bg.b = cb; bg.tx = ctx; bg.ty = cty;
                            bg.left = left; bg.top = top; bg.width = e.w; bg.height = e.h;
                            bg.color1 = applyAlpha(e.bgColor, alpha);
                        }
                        DrawEntry d = nextEntry();
                        d.kind = KIND_CHART;
                        d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                        d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                        d.color1 = applyAlpha(e.fillColor, alpha);
                        d.shapeParam = Math.max(0.5f, e.lineThickness);
                        d.shapeDir = source;
                        d.fraction = Math.min(e.statWindow,
                                com.lootmatrix.customui.client.metrics.ClientMetrics.CAPACITY);
                    }
                    if (showValue) {
                        float value = com.lootmatrix.customui.client.metrics.ClientMetrics.current(source);
                        DrawEntry d = nextEntry();
                        d.kind = KIND_TEXT;
                        d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                        d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                        d.text = rt.statValueComponent(source, value);
                        d.fontScale = e.fontSize / 9f;
                        d.shadow = e.textShadow;
                        int alphaInt = AlphaFadeHelper.clampAlphaInt(
                                Math.round(((e.textColor >>> 24) & 0xFF) * alpha));
                        d.color1 = (alphaInt << 24) | (e.textColor & 0x00FFFFFF);
                        float textWidth = rt.statValueWidth(source, value) * d.fontScale;
                        float tx0 = left + 2f;
                        if ("center".equals(e.textAlign)) {
                            tx0 = left + (e.w - textWidth) * 0.5f;
                        } else if ("right".equals(e.textAlign)) {
                            tx0 = left + e.w - textWidth - 2f;
                        }
                        d.textX = tx0;
                        // Overlaid on the chart: pin to the top edge; alone: center
                        d.textY = showChart ? top + 2f : top + (e.h - 9f * d.fontScale) * 0.5f;
                    }
                }
                case RECT -> {
                    DrawEntry d = nextEntry();
                    d.kind = KIND_RECT;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(e.fillColor, alpha);
                }
                case CIRCLE -> {
                    DrawEntry d = nextEntry();
                    d.kind = KIND_CIRCLE;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(e.fillColor, alpha);
                    d.shapeParam = Math.max(0f, e.ringThickness);
                }
                case TRIANGLE -> {
                    DrawEntry d = nextEntry();
                    d.kind = KIND_TRIANGLE;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(e.fillColor, alpha);
                    d.shapeDir = switch (e.pointDirection) {
                        case "down" -> 1;
                        case "left" -> 2;
                        case "right" -> 3;
                        default -> 0;
                    };
                }
                case BORDER -> {
                    DrawEntry d = nextEntry();
                    d.kind = KIND_BORDER;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(e.fillColor, alpha);
                    d.shapeParam = Math.max(0.5f, e.borderThickness);
                }
                case LINE -> {
                    // A line is just a thin rect vertically centered in the box;
                    // the shared rotation transform covers arbitrary angles.
                    DrawEntry d = nextEntry();
                    d.kind = KIND_RECT;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    float thickness = Math.max(0.5f, e.lineThickness);
                    d.left = left;
                    d.top = top + (e.h - thickness) * 0.5f;
                    d.width = e.w;
                    d.height = thickness;
                    d.color1 = applyAlpha(e.fillColor, alpha);
                }
                case ROUNDED_RECT -> {
                    DrawEntry d = nextEntry();
                    d.kind = KIND_ROUNDED_RECT;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(e.fillColor, alpha);
                    d.shapeParam = Math.max(0f, e.cornerRadius);
                }
                case GRADIENT_RECT -> {
                    DrawEntry d = nextEntry();
                    d.kind = KIND_GRADIENT;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(e.fillColor, alpha);
                    d.color2 = applyAlpha(e.fillColor2, alpha);
                    d.vertical = "vertical".equals(e.direction);
                }
                case ARC -> {
                    DrawEntry d = nextEntry();
                    d.kind = KIND_ARC;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(e.fillColor, alpha);
                    d.shapeParam = Math.max(0f, e.ringThickness);
                    d.shapeParam2 = e.arcStart;
                    d.shapeParam3 = e.arcSweep;
                }
                case PROGRESS -> {
                    float fraction;
                    HudElementRuntime rt = HudElementRuntime.of(e);
                    String zoneId = rt.captureZoneDataSource(e);
                    CaptureZoneHudRenderer.ZoneClientState zone = zoneId != null
                            ? CaptureZoneHudRenderer.getInstance().getZoneState(zoneId) : null;
                    String scoreboardKey = zone == null ? rt.scoreboardKey(e) : null;
                    String entityKey = zone == null && scoreboardKey == null ? rt.entityKey(e) : null;
                    com.lootmatrix.customui.hud.HudEntityBinding entityBinding =
                            entityKey != null ? rt.entityBinding(e) : null;
                    if (zone != null) {
                        fraction = zone.displayProgress;
                    } else if (scoreboardKey != null) {
                        // Smooth integer score steps into an animated sweep
                        // (sweep time is the element's 平滑时间 property)
                        int score = HudScoreboardClientCache.value(scoreboardKey);
                        fraction = rt.smoothedFraction(
                                clamp01(e.max > 0f ? score / e.max : 0f), e.progressSmoothSpeed);
                    } else if (entityKey != null && entityBinding != null && entityBinding.isNumericField()) {
                        float numeric = HudEntityClientCache.numericValue(entityKey);
                        if (Float.isNaN(numeric)) {
                            fraction = e.max > 0f ? e.value / e.max : 0f;
                        } else if (entityBinding.field == com.lootmatrix.customui.hud.HudEntityBinding.Field.HEALTH_PCT) {
                            fraction = rt.smoothedFraction(clamp01(numeric / 100f), e.progressSmoothSpeed);
                        } else {
                            fraction = rt.smoothedFraction(
                                    clamp01(e.max > 0f ? numeric / e.max : 0f), e.progressSmoothSpeed);
                        }
                    } else {
                        fraction = e.max > 0f ? e.value / e.max : 0f;
                    }
                    DrawEntry d = nextEntry();
                    d.kind = KIND_PROGRESS;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(e.barColor, alpha);
                    d.color2 = applyAlpha(e.bgColor, alpha);
                    d.fraction = clamp01(fraction);
                    // 0=horizontal 1=vertical 2=pie sweep (capture-zone style) 3=ring sweep
                    d.shapeDir = switch (e.direction) {
                        case "vertical" -> 1;
                        case "pie" -> 2;
                        case "ring" -> 3;
                        default -> 0;
                    };
                    d.shapeParam = Math.max(0f, e.ringThickness);
                }
                case IMAGE -> {
                    HudElementRuntime rt = HudElementRuntime.of(e);
                    ResourceLocation texture = rt.texture(e);
                    if (texture == null) break;
                    DrawEntry d = nextEntry();
                    d.kind = KIND_IMAGE;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.color1 = applyAlpha(0xFFFFFFFF, alpha);
                    d.texture = texture;
                    float tw = e.texW > 0 ? e.texW : 256;
                    float th = e.texH > 0 ? e.texH : 256;
                    d.u0 = e.u0 / tw; d.v0 = e.v0 / th;
                    d.u1 = e.u1 / tw; d.v1 = e.v1 / th;
                }
                case TEXT -> {
                    HudElementRuntime rt = HudElementRuntime.of(e);
                    // Scoreboard-bound text resolves {value} against the synced score
                    // (component + width cached per value, no per-frame work)
                    String scoreboardKey = rt.scoreboardKey(e);
                    String entityKey = scoreboardKey == null ? rt.entityKey(e) : null;
                    Component component;
                    int rawWidth;
                    if (scoreboardKey != null) {
                        int score = HudScoreboardClientCache.value(scoreboardKey);
                        component = rt.boundTextComponent(e, score);
                        rawWidth = rt.boundTextWidth(e, score);
                    } else if (entityKey != null) {
                        String entityValue = HudEntityClientCache.value(entityKey);
                        component = rt.boundEntityTextComponent(e, entityValue);
                        rawWidth = rt.boundEntityTextWidth(e, entityValue);
                    } else {
                        component = rt.textComponent(e);
                        rawWidth = component != null ? rt.textWidth(e) : 0;
                    }
                    if (component == null) break;
                    DrawEntry d = nextEntry();
                    d.kind = KIND_TEXT;
                    d.a = ca; d.b = cb; d.tx = ctx; d.ty = cty;
                    d.left = left; d.top = top; d.width = e.w; d.height = e.h;
                    d.text = component;
                    float fontScale = e.fontSize / 9f;
                    if (e.autoFit && rawWidth > 0) {
                        // Shrink-only fit against the element box (cached width, cheap math)
                        float fit = Math.min(e.w / rawWidth, e.h / 9f);
                        if (fit < fontScale) fontScale = fit;
                    }
                    d.fontScale = fontScale;
                    d.shadow = e.textShadow;
                    int alphaInt = AlphaFadeHelper.clampAlphaInt(Math.round(((e.textColor >>> 24) & 0xFF) * alpha));
                    d.color1 = (alphaInt << 24) | (e.textColor & 0x00FFFFFF);
                    float textWidth = rawWidth * d.fontScale;
                    float tx0 = left;
                    if ("center".equals(e.textAlign)) {
                        tx0 = left + (e.w - textWidth) * 0.5f;
                    } else if ("right".equals(e.textAlign)) {
                        tx0 = left + e.w - textWidth;
                    }
                    d.textX = tx0;
                    d.textY = top + (e.h - 9f * d.fontScale) * 0.5f;
                }
            }
        }
    }

    /** Compass arrow for edge-clamped projected elements, pointing toward the target. */
    private static void emitEdgeArrow(HudElement e, float px, float py,
                                      float dirX, float dirY, float scale, float alpha) {
        float size = Math.max(2f, e.edgeArrowSize);
        float offset = 0.5f * Math.max(e.w, e.h) * scale + size * 0.75f + 2f;
        float ax = px + dirX * offset;
        float ay = py + dirY * offset;
        // Rotate the default "up" triangle so it points along (dirX, dirY)
        float rot = (float) Math.atan2(dirY, dirX) + (float) (Math.PI / 2.0);
        float ca = (float) Math.cos(rot);
        float cb = (float) Math.sin(rot);
        DrawEntry d = nextEntry();
        d.kind = KIND_TRIANGLE;
        d.a = ca;
        d.b = cb;
        d.tx = ax - (ca * ax - cb * ay);
        d.ty = ay - (cb * ax + ca * ay);
        d.left = ax - size * 0.5f;
        d.top = ay - size * 0.5f;
        d.width = size;
        d.height = size;
        d.color1 = applyAlpha(e.edgeArrowColor, alpha);
        d.shapeDir = 0;
    }

    /** Smoothed occlusion visibility for throughWalls=false elements (throttled raycast). */
    private static float occlusionAlpha(HudElementRuntime rt, Minecraft mc, Vec3 camPos,
                                        double wx, double wy, double wz) {
        long gameTime = mc.level.getGameTime();
        if (gameTime - rt.lastOcclusionCheckTick >= OCCLUSION_INTERVAL_TICKS) {
            rt.lastOcclusionCheckTick = gameTime;
            HitResult hit = mc.level.clip(new ClipContext(
                    camPos, new Vec3(wx, wy, wz),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            rt.lastOcclusionBlocked = hit.getType() != HitResult.Type.MISS;
        }
        float target = rt.lastOcclusionBlocked ? 0f : 1f;
        rt.occlusionAlpha += (target - rt.occlusionAlpha) * 0.25f;
        return rt.occlusionAlpha;
    }

    // ==================== Batched draw phases ====================

    private static void drawCollected(GuiGraphics graphics) {
        Matrix4f pose = graphics.pose().last().pose();

        // Phase 1: all colored geometry in one draw call
        // (cull disabled: arc/triangle windings vary with sweep/direction)
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        boolean building = false;
        for (int i = 0; i < entryCount; i++) {
            DrawEntry d = ENTRIES.get(i);
            if (d.kind == KIND_IMAGE || d.kind == KIND_TEXT) continue;
            if (!building) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                building = true;
            }
            switch (d.kind) {
                case KIND_RECT -> emitQuad(buffer, pose, d, d.left, d.top, d.width, d.height, d.color1);
                case KIND_PROGRESS -> {
                    switch (d.shapeDir) {
                        case 2, 3 -> {
                            // Circular progress, clockwise sweep from 12 o'clock
                            // (matches the original capture-zone HUD mask style).
                            float cx = d.left + d.width * 0.5f;
                            float cy = d.top + d.height * 0.5f;
                            float rx = d.width * 0.5f;
                            float ry = d.height * 0.5f;
                            // pie = filled disc mask; ring = arc band (default 28% radius)
                            float thickness = d.shapeDir == 2 ? 0f
                                    : (d.shapeParam > 0f ? d.shapeParam : Math.min(rx, ry) * 0.28f);
                            emitArc(buffer, pose, d, cx, cy, rx, ry, 0f, 360f, thickness, d.color2);
                            if (d.fraction > 0f) {
                                emitArc(buffer, pose, d, cx, cy, rx, ry,
                                        0f, d.fraction * 360f, thickness, d.color1);
                            }
                        }
                        case 1 -> {
                            emitQuad(buffer, pose, d, d.left, d.top, d.width, d.height, d.color2);
                            if (d.fraction > 0f) {
                                float fh = d.height * d.fraction;
                                emitQuad(buffer, pose, d, d.left, d.top + d.height - fh, d.width, fh, d.color1);
                            }
                        }
                        default -> {
                            emitQuad(buffer, pose, d, d.left, d.top, d.width, d.height, d.color2);
                            if (d.fraction > 0f) {
                                emitQuad(buffer, pose, d, d.left, d.top, d.width * d.fraction, d.height, d.color1);
                            }
                        }
                    }
                }
                case KIND_CIRCLE -> emitArc(buffer, pose, d,
                        d.left + d.width * 0.5f, d.top + d.height * 0.5f,
                        d.width * 0.5f, d.height * 0.5f,
                        0f, 360f, d.shapeParam, d.color1);
                case KIND_ARC -> emitArc(buffer, pose, d,
                        d.left + d.width * 0.5f, d.top + d.height * 0.5f,
                        d.width * 0.5f, d.height * 0.5f,
                        d.shapeParam2, d.shapeParam3, d.shapeParam, d.color1);
                case KIND_TRIANGLE -> emitTriangle(buffer, pose, d);
                case KIND_BORDER -> emitBorder(buffer, pose, d);
                case KIND_ROUNDED_RECT -> emitRoundedRect(buffer, pose, d);
                case KIND_GRADIENT -> emitGradientQuad(buffer, pose, d);
                case KIND_CHART -> emitChart(buffer, pose, d);
                default -> { }
            }
        }
        if (building) {
            BufferUploader.drawWithShader(buffer.end());
            RenderSystem.enableCull();
        }

        // Phase 2: textured quads, batched per texture run
        ResourceLocation boundTexture = null;
        building = false;
        for (int i = 0; i < entryCount; i++) {
            DrawEntry d = ENTRIES.get(i);
            if (d.kind != KIND_IMAGE || d.texture == null) continue;
            if (!d.texture.equals(boundTexture)) {
                if (building) {
                    BufferUploader.drawWithShader(buffer.end());
                    building = false;
                }
                boundTexture = d.texture;
                RenderSystem.setShaderTexture(0, boundTexture);
            }
            if (!building) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
                buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                building = true;
            }
            emitTexturedQuad(buffer, pose, d);
        }
        if (building) {
            BufferUploader.drawWithShader(buffer.end());
        }
        RenderSystem.disableBlend();

        // Phase 3: all text in one batch
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        MultiBufferSource.BufferSource textBuffers = mc.renderBuffers().bufferSource();
        boolean anyText = false;
        for (int i = 0; i < entryCount; i++) {
            DrawEntry d = ENTRIES.get(i);
            if (d.kind != KIND_TEXT || d.text == null) continue;
            anyText = true;
            float fa = d.a * d.fontScale;
            float fb = d.b * d.fontScale;
            float ftx = d.a * d.textX - d.b * d.textY + d.tx;
            float fty = d.b * d.textX + d.a * d.textY + d.ty;
            TEXT_MATRIX.set(pose);
            // column-major: local affine [fa -fb 0 ftx; fb fa 0 fty]
            TEXT_MATRIX.mul(SCRATCH_AFFINE.identity()
                    .m00(fa).m01(fb)
                    .m10(-fb).m11(fa)
                    .m30(ftx).m31(fty));
            font.drawInBatch(d.text, 0f, 0f, d.color1, d.shadow,
                    TEXT_MATRIX, textBuffers, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        }
        if (anyText) {
            textBuffers.endBatch();
        }

        // Release object references so replaced templates/components can be collected
        for (int i = 0; i < entryCount; i++) {
            ENTRIES.get(i).release();
        }
    }

    private static final Matrix4f SCRATCH_AFFINE = new Matrix4f();

    private static void emitQuad(BufferBuilder buffer, Matrix4f pose, DrawEntry d,
                                 float x, float y, float w, float h, int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        if (a <= 0f) return;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float x2 = x + w, y2 = y + h;
        buffer.vertex(pose, tX(d, x, y), tY(d, x, y), 0).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x, y2), tY(d, x, y2), 0).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x2, y2), tY(d, x2, y2), 0).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x2, y), tY(d, x2, y), 0).color(r, g, b, a).endVertex();
    }

    private static void emitTexturedQuad(BufferBuilder buffer, Matrix4f pose, DrawEntry d) {
        float a = ((d.color1 >>> 24) & 0xFF) / 255f;
        if (a <= 0f) return;
        float r = ((d.color1 >>> 16) & 0xFF) / 255f;
        float g = ((d.color1 >>> 8) & 0xFF) / 255f;
        float b = (d.color1 & 0xFF) / 255f;
        float x = d.left, y = d.top, x2 = d.left + d.width, y2 = d.top + d.height;
        buffer.vertex(pose, tX(d, x, y), tY(d, x, y), 0).uv(d.u0, d.v0).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x, y2), tY(d, x, y2), 0).uv(d.u0, d.v1).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x2, y2), tY(d, x2, y2), 0).uv(d.u1, d.v1).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x2, y), tY(d, x2, y), 0).uv(d.u1, d.v0).color(r, g, b, a).endVertex();
    }

    /**
     * Elliptical arc band (or filled pie slice when {@code thickness} <= 0 / >= min radius)
     * as a degenerate quad strip. Angles in degrees, clockwise from 12 o'clock.
     * Segment count adapts to the sweep; everything is emitted inline (no allocation).
     */
    private static void emitArc(BufferBuilder buffer, Matrix4f pose, DrawEntry d,
                                float cx, float cy, float rx, float ry,
                                float startDeg, float sweepDeg, float thickness, int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        if (a <= 0f || rx <= 0f || ry <= 0f || sweepDeg == 0f) return;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        boolean filled = thickness <= 0f || thickness >= Math.min(rx, ry);
        float rxIn = filled ? 0f : rx - thickness;
        float ryIn = filled ? 0f : ry - thickness;

        int segments = Math.max(4, Math.min(90, (int) Math.ceil(Math.abs(sweepDeg) / 8f)));
        float startRad = startDeg * DEG_TO_RAD;
        float stepRad = sweepDeg * DEG_TO_RAD / segments;
        float sin0 = (float) Math.sin(startRad);
        float cos0 = (float) Math.cos(startRad);
        for (int i = 1; i <= segments; i++) {
            float angle = startRad + stepRad * i;
            float sin1 = (float) Math.sin(angle);
            float cos1 = (float) Math.cos(angle);
            float ox0 = cx + sin0 * rx, oy0 = cy - cos0 * ry;
            float ox1 = cx + sin1 * rx, oy1 = cy - cos1 * ry;
            float ix0 = cx + sin0 * rxIn, iy0 = cy - cos0 * ryIn;
            float ix1 = cx + sin1 * rxIn, iy1 = cy - cos1 * ryIn;
            buffer.vertex(pose, tX(d, ix0, iy0), tY(d, ix0, iy0), 0).color(r, g, b, a).endVertex();
            buffer.vertex(pose, tX(d, ox0, oy0), tY(d, ox0, oy0), 0).color(r, g, b, a).endVertex();
            buffer.vertex(pose, tX(d, ox1, oy1), tY(d, ox1, oy1), 0).color(r, g, b, a).endVertex();
            buffer.vertex(pose, tX(d, ix1, iy1), tY(d, ix1, iy1), 0).color(r, g, b, a).endVertex();
            sin0 = sin1;
            cos0 = cos1;
        }
    }

    /** Filled triangle as one degenerate quad. */
    private static void emitTriangle(BufferBuilder buffer, Matrix4f pose, DrawEntry d) {
        float a = ((d.color1 >>> 24) & 0xFF) / 255f;
        if (a <= 0f) return;
        float r = ((d.color1 >>> 16) & 0xFF) / 255f;
        float g = ((d.color1 >>> 8) & 0xFF) / 255f;
        float b = (d.color1 & 0xFF) / 255f;
        float left = d.left, top = d.top, right = d.left + d.width, bottom = d.top + d.height;
        float cx = d.left + d.width * 0.5f, cy = d.top + d.height * 0.5f;
        float x1, y1, x2, y2, x3, y3;
        switch (d.shapeDir) {
            case 1 -> { x1 = left; y1 = top; x2 = right; y2 = top; x3 = cx; y3 = bottom; }        // down
            case 2 -> { x1 = right; y1 = top; x2 = right; y2 = bottom; x3 = left; y3 = cy; }      // left
            case 3 -> { x1 = left; y1 = top; x2 = left; y2 = bottom; x3 = right; y3 = cy; }       // right
            default -> { x1 = cx; y1 = top; x2 = left; y2 = bottom; x3 = right; y3 = bottom; }    // up
        }
        buffer.vertex(pose, tX(d, x1, y1), tY(d, x1, y1), 0).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x2, y2), tY(d, x2, y2), 0).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x3, y3), tY(d, x3, y3), 0).color(r, g, b, a).endVertex();
        buffer.vertex(pose, tX(d, x3, y3), tY(d, x3, y3), 0).color(r, g, b, a).endVertex();
    }

    /**
     * Adaptive line chart for STAT elements: maps the metric ring buffer into
     * the element box (auto min/max with 8% vertical margin), each segment a
     * thin quad in the shared colored-geometry batch. Allocation-free.
     */
    private static void emitChart(BufferBuilder buffer, Matrix4f pose, DrawEntry d) {
        float a = ((d.color1 >>> 24) & 0xFF) / 255f;
        if (a <= 0f) return;
        int source = d.shapeDir;
        int window = Math.max(2, (int) d.fraction);
        float[] data = com.lootmatrix.customui.client.metrics.ClientMetrics.seriesData(source);
        int cap = com.lootmatrix.customui.client.metrics.ClientMetrics.CAPACITY;
        int count = Math.min(com.lootmatrix.customui.client.metrics.ClientMetrics.seriesCount(source), window);
        if (count < 2) return;
        int head = com.lootmatrix.customui.client.metrics.ClientMetrics.seriesHead(source);
        int start = head - count;

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float v = data[(start + i + cap) % cap];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float range = max - min;
        if (range < 0.0001f) {
            min -= 1f;
            range = 2f;
        }
        float marginY = d.height * 0.08f;
        float usableH = d.height - marginY * 2f;

        float r = ((d.color1 >>> 16) & 0xFF) / 255f;
        float g = ((d.color1 >>> 8) & 0xFF) / 255f;
        float b = (d.color1 & 0xFF) / 255f;
        float half = d.shapeParam * 0.5f;
        float stepX = d.width / (count - 1);

        float prevX = d.left;
        float prevY = d.top + marginY + (1f - (data[(start + cap) % cap] - min) / range) * usableH;
        for (int i = 1; i < count; i++) {
            float x = d.left + stepX * i;
            float v = data[(start + i + cap) % cap];
            float y = d.top + marginY + (1f - (v - min) / range) * usableH;
            float dx = x - prevX;
            float dy = y - prevY;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 0.0001f) {
                float nx = -dy / len * half;
                float ny = dx / len * half;
                buffer.vertex(pose, tX(d, prevX + nx, prevY + ny), tY(d, prevX + nx, prevY + ny), 0)
                        .color(r, g, b, a).endVertex();
                buffer.vertex(pose, tX(d, prevX - nx, prevY - ny), tY(d, prevX - nx, prevY - ny), 0)
                        .color(r, g, b, a).endVertex();
                buffer.vertex(pose, tX(d, x - nx, y - ny), tY(d, x - nx, y - ny), 0)
                        .color(r, g, b, a).endVertex();
                buffer.vertex(pose, tX(d, x + nx, y + ny), tY(d, x + nx, y + ny), 0)
                        .color(r, g, b, a).endVertex();
            }
            prevX = x;
            prevY = y;
        }
    }

    /** Hollow rectangle outline as four bars. */
    private static void emitBorder(BufferBuilder buffer, Matrix4f pose, DrawEntry d) {
        float t = Math.min(d.shapeParam, Math.min(d.width, d.height) * 0.5f);
        if (t <= 0f) return;
        emitQuad(buffer, pose, d, d.left, d.top, d.width, t, d.color1);
        emitQuad(buffer, pose, d, d.left, d.top + d.height - t, d.width, t, d.color1);
        float innerH = d.height - t * 2f;
        if (innerH > 0f) {
            emitQuad(buffer, pose, d, d.left, d.top + t, t, innerH, d.color1);
            emitQuad(buffer, pose, d, d.left + d.width - t, d.top + t, t, innerH, d.color1);
        }
    }

    /** Rounded rectangle: three rects plus four quarter-disc corners. */
    private static void emitRoundedRect(BufferBuilder buffer, Matrix4f pose, DrawEntry d) {
        float radius = Math.min(d.shapeParam, Math.min(d.width, d.height) * 0.5f);
        if (radius <= 0.5f) {
            emitQuad(buffer, pose, d, d.left, d.top, d.width, d.height, d.color1);
            return;
        }
        float left = d.left, top = d.top, w = d.width, h = d.height;
        emitQuad(buffer, pose, d, left, top + radius, w, h - radius * 2f, d.color1);
        emitQuad(buffer, pose, d, left + radius, top, w - radius * 2f, radius, d.color1);
        emitQuad(buffer, pose, d, left + radius, top + h - radius, w - radius * 2f, radius, d.color1);
        // Corners (angles clockwise from 12 o'clock)
        emitArc(buffer, pose, d, left + radius, top + radius, radius, radius, 270f, 90f, 0f, d.color1);          // top-left
        emitArc(buffer, pose, d, left + w - radius, top + radius, radius, radius, 0f, 90f, 0f, d.color1);        // top-right
        emitArc(buffer, pose, d, left + w - radius, top + h - radius, radius, radius, 90f, 90f, 0f, d.color1);   // bottom-right
        emitArc(buffer, pose, d, left + radius, top + h - radius, radius, radius, 180f, 90f, 0f, d.color1);      // bottom-left
    }

    /** Two-color linear gradient quad (horizontal: left→right, vertical: top→bottom). */
    private static void emitGradientQuad(BufferBuilder buffer, Matrix4f pose, DrawEntry d) {
        float a1 = ((d.color1 >>> 24) & 0xFF) / 255f;
        float a2 = ((d.color2 >>> 24) & 0xFF) / 255f;
        if (a1 <= 0f && a2 <= 0f) return;
        float r1 = ((d.color1 >>> 16) & 0xFF) / 255f;
        float g1 = ((d.color1 >>> 8) & 0xFF) / 255f;
        float b1 = (d.color1 & 0xFF) / 255f;
        float r2 = ((d.color2 >>> 16) & 0xFF) / 255f;
        float g2 = ((d.color2 >>> 8) & 0xFF) / 255f;
        float b2 = (d.color2 & 0xFF) / 255f;
        float x = d.left, y = d.top, x2 = d.left + d.width, y2 = d.top + d.height;
        if (d.vertical) {
            // TL=c1, BL=c2, BR=c2, TR=c1
            buffer.vertex(pose, tX(d, x, y), tY(d, x, y), 0).color(r1, g1, b1, a1).endVertex();
            buffer.vertex(pose, tX(d, x, y2), tY(d, x, y2), 0).color(r2, g2, b2, a2).endVertex();
            buffer.vertex(pose, tX(d, x2, y2), tY(d, x2, y2), 0).color(r2, g2, b2, a2).endVertex();
            buffer.vertex(pose, tX(d, x2, y), tY(d, x2, y), 0).color(r1, g1, b1, a1).endVertex();
        } else {
            // TL=c1, BL=c1, BR=c2, TR=c2
            buffer.vertex(pose, tX(d, x, y), tY(d, x, y), 0).color(r1, g1, b1, a1).endVertex();
            buffer.vertex(pose, tX(d, x, y2), tY(d, x, y2), 0).color(r1, g1, b1, a1).endVertex();
            buffer.vertex(pose, tX(d, x2, y2), tY(d, x2, y2), 0).color(r2, g2, b2, a2).endVertex();
            buffer.vertex(pose, tX(d, x2, y), tY(d, x2, y), 0).color(r2, g2, b2, a2).endVertex();
        }
    }

    private static float tX(DrawEntry d, float x, float y) {
        return d.a * x - d.b * y + d.tx;
    }

    private static float tY(DrawEntry d, float x, float y) {
        return d.b * x + d.a * y + d.ty;
    }

    private static int applyAlpha(int argb, float alpha) {
        int original = (argb >>> 24) & 0xFF;
        int result = Math.round(original * Math.min(1f, alpha));
        return (Math.min(255, Math.max(0, result)) << 24) | (argb & 0x00FFFFFF);
    }

    private static float clamp01(float v) {
        if (v <= 0f) return 0f;
        if (v >= 1f) return 1f;
        return v;
    }
}
