package com.lootmatrix.customui.client;

import com.lootmatrix.customui.client.render.BatchedRenderHelper;
import com.lootmatrix.customui.client.render.WorldToScreenUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side HUD renderer for capture zones.
 *
 * Features:
 * - Clockwise sweep animation (fan from 12 o'clock) showing capture progress
 * - 3D→2D projected zone markers using WorldToScreenUtil (投影轮子)
 * - Distance-based icon scaling (near=large, far=small)
 * - Screen-edge clamping with compass-like arrow indicators for off-screen zones
 * - Smooth progress bar below zone indicator
 * - Fade-in/fade-out transition animations
 * - Scale bounce on capture state changes
 * - Team color integration from scoreboard
 */
@OnlyIn(Dist.CLIENT)
public class CaptureZoneHudRenderer {

    private static final CaptureZoneHudRenderer INSTANCE = new CaptureZoneHudRenderer();
    private static final BatchedRenderHelper RECT_BATCHER = new BatchedRenderHelper();
    private static final int FULL_BRIGHT = 15728880;
    public static CaptureZoneHudRenderer getInstance() { return INSTANCE; }

    /** Base size of the capture indicator square. */
    private static final int BASE_INDICATOR_SIZE = 48;
    /** Number of segments for the sweep arc. */
    private static final int SWEEP_SEGMENTS = 64;
    /** Max render distance for 3D markers (blocks). */
    private static final double MARKER_MAX_DIST = 200.0;
    /** Distance at which indicator is at full size. */
    private static final double MARKER_FULL_SIZE_DIST = 16.0;
    /** Distance at which indicator is at minimum size (0.4x). */
    private static final double MARKER_MIN_SIZE_DIST = 150.0;
    /** Minimum scale factor for distant markers. */
    private static final float MARKER_MIN_SCALE = 0.4f;
    /** Screen edge padding for clamped markers. */
    private static final int EDGE_PADDING = 30;
    /** Progress bar height in pixels. */
    private static final int PROGRESS_BAR_HEIGHT = 4;
    /** Progress bar width in pixels. */
    private static final int PROGRESS_BAR_WIDTH = 60;

    private final Map<String, ZoneClientState> zones = new LinkedHashMap<>();

    public static class ZoneClientState {
        public String zoneId;
        public String displayName;
        public float progress;
        @Nullable public String capturingTeam;
        @Nullable public String ownerTeam;
        public boolean contested;
        /** Smoothed progress for animation. */
        public float displayProgress;
        /** Tick when last updated (for fadeout). */
        public long lastUpdateTick;
        /** Fade-in alpha (0→1 over 10 ticks). */
        public float fadeAlpha = 0f;
        /** Scale bounce factor (1.0 = normal, >1.0 = bounce up). */
        public float scaleBounce = 1.0f;
        /** Previous owner for state-change detection. */
        @Nullable public String prevOwnerTeam;
        /** Previous capturing team for state-change detection. */
        @Nullable public String prevCapturingTeam;
        public String cachedLetter = "?";
        public String cachedDisplayName = "";
        public int cachedDisplayNameWidth = -1;
        public String cachedStatusText = "NEUTRAL";
        public int cachedStatusWidth = -1;
        public int cachedStatusProgressPercent = Integer.MIN_VALUE;
        public boolean cachedStatusContested = false;
        public boolean cachedStatusHasOwner = false;
        public boolean cachedStatusHasCapturingTeam = false;
        public String cachedDistanceText = "";
        public int cachedDistanceWidth = -1;
        public int cachedDistanceMeters = Integer.MIN_VALUE;
    }

    public void updateZoneState(String zoneId, String displayName, float progress,
                                 @Nullable String capturingTeam, @Nullable String ownerTeam,
                                 boolean contested) {
        ZoneClientState state = zones.computeIfAbsent(zoneId, id -> {
            ZoneClientState s = new ZoneClientState();
            s.zoneId = id;
            return s;
        });
        state.displayName = displayName;
        state.progress = progress;
        // Detect state changes for bounce animation
        if (!java.util.Objects.equals(ownerTeam, state.ownerTeam) ||
            !java.util.Objects.equals(capturingTeam, state.capturingTeam)) {
            state.scaleBounce = 1.3f; // trigger bounce
        }
        state.prevOwnerTeam = state.ownerTeam;
        state.prevCapturingTeam = state.capturingTeam;
        state.capturingTeam = capturingTeam;
        state.ownerTeam = ownerTeam;
        state.contested = contested;
        Minecraft mc = Minecraft.getInstance();
        state.lastUpdateTick = mc.level != null ? mc.level.getGameTime() : 0;
    }

    @Nullable
    public ZoneClientState getZoneState(String zoneId) {
        return zones.get(zoneId);
    }

    public void clearAll() {
        zones.clear();
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type()) return;
        if (zones.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        GuiGraphics gfx = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        long gameTick = mc.level.getGameTime();
        float partialTick = event.getPartialTick();

        CaptureZoneBoundaryRenderer boundaryRenderer = CaptureZoneBoundaryRenderer.getInstance();
        MultiBufferSource.BufferSource textBuffers = mc.renderBuffers().bufferSource();

        int fallbackIndex = 0; // For zones without geometry (fallback: top-center stacked)
        for (ZoneClientState state : zones.values()) {
            // Fade out zones not updated recently (> 5 seconds)
            long ticksSinceUpdate = gameTick - state.lastUpdateTick;
            if (ticksSinceUpdate > 100) {
                state.fadeAlpha = Math.max(0f, state.fadeAlpha - 0.05f);
                if (state.fadeAlpha <= 0f) continue;
            } else {
                // Fade in
                state.fadeAlpha = Math.min(1f, state.fadeAlpha + 0.1f);
            }

            // Smooth progress animation
            float targetProgress = state.progress;
            state.displayProgress += (targetProgress - state.displayProgress) * 0.15f;
            if (Math.abs(state.displayProgress - targetProgress) < 0.001f) {
                state.displayProgress = targetProgress;
            }

            // Decay scale bounce
            state.scaleBounce += (1.0f - state.scaleBounce) * 0.15f;
            if (Math.abs(state.scaleBounce - 1.0f) < 0.005f) state.scaleBounce = 1.0f;

            // Try 3D→2D projection using WorldToScreenUtil (投影轮子)
            Vec3 worldCenter = boundaryRenderer.getZoneWorldCenter(state.zoneId);
            if (worldCenter != null && mc.player != null) {
                renderProjectedZoneMarker(gfx, font, textBuffers, state, worldCenter,
                        screenW, screenH, partialTick, gameTick);
            } else {
                // Fallback: render at top-center stacked
                renderFallbackIndicator(gfx, font, textBuffers, state, screenW, screenH, fallbackIndex, partialTick);
                fallbackIndex++;
            }
        }
        textBuffers.endBatch();
    }

    /**
     * Render a zone marker projected from 3D world space to 2D screen space.
     * Uses WorldToScreenUtil for projection, with screen-edge clamping and distance scaling.
     */
    private void renderProjectedZoneMarker(GuiGraphics gfx, Font font, MultiBufferSource.BufferSource textBuffers,
                                            ZoneClientState state,
                                            Vec3 worldCenter, int screenW, int screenH,
                                            float partialTick, long gameTick) {
        Minecraft mc = Minecraft.getInstance();
        double dist = mc.player.position().distanceTo(worldCenter);
        if (dist > MARKER_MAX_DIST) return;

        // Distance-based scale factor
        float distScale;
        if (dist <= MARKER_FULL_SIZE_DIST) {
            distScale = 1.0f;
        } else if (dist >= MARKER_MIN_SIZE_DIST) {
            distScale = MARKER_MIN_SCALE;
        } else {
            float t = (float) ((dist - MARKER_FULL_SIZE_DIST) / (MARKER_MIN_SIZE_DIST - MARKER_FULL_SIZE_DIST));
            distScale = 1.0f - t * (1.0f - MARKER_MIN_SCALE);
        }
        float scale = distScale * state.scaleBounce;
        int indicatorSize = Math.round(BASE_INDICATOR_SIZE * scale);
        if (indicatorSize < 8) indicatorSize = 8;

        // Project world center to screen
        Vec3 screenResult = WorldToScreenUtil.worldToScreen(worldCenter);

        float renderX, renderY;
        boolean isClamped = false;

        if (screenResult.z > 0 && WorldToScreenUtil.isOnScreen(screenResult)) {
            // On-screen: use projected position
            renderX = (float) screenResult.x;
            renderY = (float) screenResult.y;
        } else {
            // Off-screen or behind camera: clamp to screen edge
            isClamped = true;
            float sx, sy;
            if (screenResult.z <= 0) {
                // Behind camera: flip direction
                sx = (float) (screenW - screenResult.x);
                sy = (float) (screenH - screenResult.y);
            } else {
                sx = (float) screenResult.x;
                sy = (float) screenResult.y;
            }

            // Direction from screen center to projected point
            float cx = screenW / 2f;
            float cy = screenH / 2f;
            float dx = sx - cx;
            float dy = sy - cy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 0.001f) {
                dx /= len;
                dy /= len;
            } else {
                dx = 0; dy = -1; // default: up
            }

            // Clamp to edge
            float maxDx = (screenW / 2f) - EDGE_PADDING;
            float maxDy = (screenH / 2f) - EDGE_PADDING;
            float t = Math.min(
                    Math.abs(dx) > 0.001f ? maxDx / Math.abs(dx) : Float.MAX_VALUE,
                    Math.abs(dy) > 0.001f ? maxDy / Math.abs(dy) : Float.MAX_VALUE
            );
            renderX = cx + dx * t;
            renderY = cy + dy * t;
        }

        // Apply fade alpha
        float alpha = state.fadeAlpha;
        int cx = Math.round(renderX);
        int cy = Math.round(renderY);
        int half = indicatorSize / 2;

        // Render the indicator
        renderIndicatorAt(gfx, font, textBuffers, state, cx, cy, indicatorSize, alpha, isClamped, dist);

        // If clamped, draw a small directional arrow
        if (isClamped) {
            renderEdgeArrow(gfx, cx, cy, screenW / 2f, screenH / 2f,
                    getTeamColor(state.ownerTeam != null ? state.ownerTeam : state.capturingTeam, alpha));
        }
    }

    /**
     * Fallback rendering when no 3D geometry is available — stacked at top center.
     */
    private void renderFallbackIndicator(GuiGraphics gfx, Font font, MultiBufferSource.BufferSource textBuffers,
                                          ZoneClientState state,
                                          int screenW, int screenH, int index, float partialTick) {
        int cx = screenW / 2;
        int cy = 50 + index * (BASE_INDICATOR_SIZE + 24);
        renderIndicatorAt(gfx, font, textBuffers, state, cx, cy, BASE_INDICATOR_SIZE, state.fadeAlpha, false, 0);
    }

    /**
     * Render a zone indicator at a specific screen position with given size and alpha.
     */
    private void renderIndicatorAt(GuiGraphics gfx, Font font, MultiBufferSource.BufferSource textBuffers,
                                    ZoneClientState state,
                                    int cx, int cy, int size, float alpha, boolean clamped, double dist) {
        int half = size / 2;
        int left = cx - half;
        int top = cy - half;
        refreshTextCache(font, state, clamped, dist);

        Matrix4f matrix = gfx.pose().last().pose();
        RECT_BATCHER.beginColoredQuads(matrix);
        RECT_BATCHER.addQuadARGB(matrix, left + 2, top + 2, size, size, applyAlpha(0x40000000, alpha));
        RECT_BATCHER.addQuadARGB(matrix, left, top, size, size, applyAlpha(0xC0111111, alpha));

        int borderColor = state.contested ? applyAlpha(0xFFFF8800, alpha) :
                (state.ownerTeam != null ? applyAlpha(0xFF44FF44, alpha) : applyAlpha(0xFF4488FF, alpha));
        appendOutline(matrix, left - 1, top - 1, left + size + 1, top + size + 1, borderColor);

        int barLeft = cx - PROGRESS_BAR_WIDTH / 2;
        int barTop = top + size + 3;
        appendProgressBar(matrix, barLeft, barTop, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT,
                state.displayProgress, state, alpha);
        RECT_BATCHER.end();

        // Clockwise sweep from 12 o'clock
        if (state.displayProgress > 0.001f) {
            int sweepColor = state.contested ? applyAlpha(0x80FF8800, alpha) :
                    getTeamColor(state.capturingTeam, alpha * 0.5f);
            renderClockwiseSweep(gfx, cx, cy, half - 2, state.displayProgress, sweepColor);
        }

        // Scale font rendering if indicator is scaled
        float fontScale = size / (float) BASE_INDICATOR_SIZE;
        if (fontScale < 0.5f) fontScale = 0.5f;

        gfx.pose().pushPose();
        gfx.pose().translate(cx, cy - 4 * fontScale, 0);
        gfx.pose().scale(fontScale, fontScale, 1f);
        drawBatchedText(font, textBuffers, gfx.pose().last().pose(),
                state.cachedLetter, -font.width(state.cachedLetter) / 2.0f, 0,
                applyAlpha(0xFFFFFFFF, alpha), true);
        gfx.pose().popPose();

        // Zone name below progress bar
        if (!clamped) {
            drawBatchedText(font, textBuffers, matrix,
                    state.cachedDisplayName,
                    cx - state.cachedDisplayNameWidth / 2.0f,
                    barTop + PROGRESS_BAR_HEIGHT + 2,
                    applyAlpha(0xFFCCCCCC, alpha), true);
        }

        if (!clamped) {
            drawBatchedText(font, textBuffers, matrix,
                    state.cachedStatusText,
                    cx - state.cachedStatusWidth / 2.0f,
                    barTop + PROGRESS_BAR_HEIGHT + 12,
                    getStatusColor(state, alpha), true);
        }

        // Distance indicator (only for projected markers with distance > 0)
        if (dist > 1.0 && !clamped) {
            drawBatchedText(font, textBuffers, matrix,
                    state.cachedDistanceText,
                    cx - state.cachedDistanceWidth / 2.0f,
                    barTop + PROGRESS_BAR_HEIGHT + 22,
                    applyAlpha(0xFF999999, alpha), true);
        }
    }

    private void refreshTextCache(Font font, ZoneClientState state, boolean clamped, double dist) {
        String displayName = state.displayName == null ? "" : state.displayName;
        if (!displayName.equals(state.cachedDisplayName) || state.cachedDisplayNameWidth < 0) {
            state.cachedDisplayName = displayName;
            state.cachedDisplayNameWidth = font.width(displayName);
            state.cachedLetter = displayName.isEmpty() ? "?" : displayName.substring(0, 1).toUpperCase();
        }

        boolean hasOwner = state.ownerTeam != null;
        boolean hasCapturingTeam = state.capturingTeam != null;
        int progressPercent = Math.round(state.displayProgress * 100f);
        if (state.cachedStatusContested != state.contested
                || state.cachedStatusHasOwner != hasOwner
                || state.cachedStatusHasCapturingTeam != hasCapturingTeam
                || state.cachedStatusProgressPercent != progressPercent
                || state.cachedStatusWidth < 0) {
            if (state.contested) {
                state.cachedStatusText = "CONTESTED";
            } else if (hasOwner) {
                state.cachedStatusText = "CAPTURED";
            } else if (hasCapturingTeam) {
                state.cachedStatusText = "CAPTURING " + progressPercent + "%";
            } else if (state.displayProgress > 0f) {
                state.cachedStatusText = "DECAYING";
            } else {
                state.cachedStatusText = "NEUTRAL";
            }
            state.cachedStatusWidth = font.width(state.cachedStatusText);
            state.cachedStatusContested = state.contested;
            state.cachedStatusHasOwner = hasOwner;
            state.cachedStatusHasCapturingTeam = hasCapturingTeam;
            state.cachedStatusProgressPercent = progressPercent;
        }

        int distanceMeters = (!clamped && dist > 1.0) ? Math.round((float) dist) : Integer.MIN_VALUE;
        if (state.cachedDistanceMeters != distanceMeters || state.cachedDistanceWidth < 0) {
            state.cachedDistanceMeters = distanceMeters;
            state.cachedDistanceText = distanceMeters == Integer.MIN_VALUE ? "" : distanceMeters + "m";
            state.cachedDistanceWidth = state.cachedDistanceText.isEmpty() ? 0 : font.width(state.cachedDistanceText);
        }
    }

    private int getStatusColor(ZoneClientState state, float alpha) {
        if (state.contested) {
            return applyAlpha(0xFFFF8800, alpha);
        }
        if (state.ownerTeam != null) {
            return applyAlpha(0xFF44FF44, alpha);
        }
        if (state.capturingTeam != null) {
            return applyAlpha(0xFF88CCFF, alpha);
        }
        if (state.displayProgress > 0f) {
            return applyAlpha(0xFFFF6666, alpha);
        }
        return applyAlpha(0xFF888888, alpha);
    }

    private void appendProgressBar(Matrix4f matrix, int x, int y, int width, int height,
                                   float progress, ZoneClientState state, float alpha) {
        RECT_BATCHER.addQuadARGB(matrix, x, y, width, height, applyAlpha(0xC0222222, alpha));

        int fillWidth = Math.round(width * Math.min(1f, progress));
        if (fillWidth > 0) {
            int fillColor;
            if (state.contested) {
                fillColor = applyAlpha(0xFFFF8800, alpha);
            } else if (state.ownerTeam != null) {
                fillColor = applyAlpha(0xFF44FF44, alpha);
            } else {
                fillColor = getTeamColor(state.capturingTeam, alpha);
            }
            RECT_BATCHER.addQuadARGB(matrix, x, y, fillWidth, height, fillColor);
        }

        appendOutline(matrix, x - 1, y - 1, x + width + 1, y + height + 1, applyAlpha(0x80FFFFFF, alpha));
    }

    private void appendOutline(Matrix4f matrix, int x1, int y1, int x2, int y2, int color) {
        RECT_BATCHER.addQuadARGB(matrix, x1, y1, x2 - x1, 1, color);
        RECT_BATCHER.addQuadARGB(matrix, x1, y2 - 1, x2 - x1, 1, color);
        RECT_BATCHER.addQuadARGB(matrix, x1, y1, 1, y2 - y1, color);
        RECT_BATCHER.addQuadARGB(matrix, x2 - 1, y1, 1, y2 - y1, color);
    }

    private void drawBatchedText(Font font, MultiBufferSource.BufferSource textBuffers,
                                 Matrix4f matrix, String text, float x, float y,
                                 int color, boolean shadow) {
        font.drawInBatch(
                text,
                x,
                y,
                color,
                shadow,
                matrix,
                textBuffers,
                Font.DisplayMode.NORMAL,
                0,
                FULL_BRIGHT
        );
    }

    /**
     * Render a horizontal progress bar with fill and outline.
     */
    private void renderProgressBar(GuiGraphics gfx, int x, int y, int width, int height,
                                    float progress, ZoneClientState state, float alpha) {
        // Background
        gfx.fill(x, y, x + width, y + height, applyAlpha(0xC0222222, alpha));

        // Fill
        int fillWidth = Math.round(width * Math.min(1f, progress));
        if (fillWidth > 0) {
            int fillColor;
            if (state.contested) {
                fillColor = applyAlpha(0xFFFF8800, alpha);
            } else if (state.ownerTeam != null) {
                fillColor = applyAlpha(0xFF44FF44, alpha);
            } else {
                fillColor = getTeamColor(state.capturingTeam, alpha);
            }
            gfx.fill(x, y, x + fillWidth, y + height, fillColor);
        }

        // Outline
        drawOutline(gfx, x - 1, y - 1, x + width + 1, y + height + 1, applyAlpha(0x80FFFFFF, alpha));
    }

    /**
     * Render a clockwise sweep (pie/fan) from 12 o'clock position.
     */
    private void renderClockwiseSweep(GuiGraphics gfx, int cx, int cy, int radius,
                                       float progress, int color) {
        if (progress <= 0) return;
        progress = Math.min(1f, progress);

        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;

        float endAngle = progress * 2f * (float) Math.PI;
        int segments = (int) (SWEEP_SEGMENTS * progress) + 1;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        Matrix4f matrix = gfx.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        buf.vertex(matrix, cx, cy, 0).color(r, g, b, a).endVertex();

        for (int i = 0; i <= segments; i++) {
            float angle = (float) i / segments * endAngle - (float) Math.PI / 2f;
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            float maxComponent = Math.max(Math.abs(dx), Math.abs(dy));
            if (maxComponent > 0) {
                dx = dx / maxComponent * radius;
                dy = dy / maxComponent * radius;
            }
            buf.vertex(matrix, cx + dx, cy + dy, 0).color(r, g, b, a).endVertex();
        }

        BufferBuilder.RenderedBuffer rendered = buf.end();
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(rendered);
        RenderSystem.disableBlend();
    }

    /**
     * Render a directional arrow at the screen edge pointing toward screen center.
     */
    private void renderEdgeArrow(GuiGraphics gfx, float cx, float cy,
                                  float centerX, float centerY, int color) {
        float dx = centerX - cx;
        float dy = centerY - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;
        dx /= len;
        dy /= len;

        // Arrow tip at cx, cy pointing inward
        float tipX = cx + dx * 8;
        float tipY = cy + dy * 8;
        // Perpendicular
        float px = -dy * 5;
        float py = dx * 5;

        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Matrix4f matrix = gfx.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(matrix, tipX, tipY, 0).color(r, g, b, a).endVertex();
        buf.vertex(matrix, cx - dx * 6 + px, cy - dy * 6 + py, 0).color(r, g, b, a * 0.5f).endVertex();
        buf.vertex(matrix, cx - dx * 6 - px, cy - dy * 6 - py, 0).color(r, g, b, a * 0.5f).endVertex();
        BufferUploader.drawWithShader(buf.end());
        RenderSystem.disableBlend();
    }

    private int getTeamColor(@Nullable String teamName, float alpha) {
        int baseColor = 0x4488FF;
        if (teamName != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                var team = mc.level.getScoreboard().getPlayerTeam(teamName);
                if (team != null && team.getColor().getColor() != null) {
                    baseColor = team.getColor().getColor();
                }
            }
        }
        int a = Math.round(Math.min(1f, alpha) * 255);
        return (a << 24) | (baseColor & 0x00FFFFFF);
    }

    private int applyAlpha(int color, float alphaMultiplier) {
        int origAlpha = (color >> 24) & 0xFF;
        int newAlpha = Math.round(origAlpha * Math.min(1f, alphaMultiplier));
        return (newAlpha << 24) | (color & 0x00FFFFFF);
    }

    private void drawOutline(GuiGraphics gfx, int x1, int y1, int x2, int y2, int c) {
        gfx.fill(x1, y1, x2, y1 + 1, c);
        gfx.fill(x1, y2 - 1, x2, y2, c);
        gfx.fill(x1, y1, x1 + 1, y2, c);
        gfx.fill(x2 - 1, y1, x2, y2, c);
    }
}
