package com.lootmatrix.customui.client;

import com.lootmatrix.customui.client.render.RenderResourceCache;
import com.lootmatrix.customui.config.HotbarConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kill message overlay renderer.
 * Displays kill feed in top-right corner.
 * Icon display rules:
 *   a. TACZ gun kill → TACZ gun HUD icon + headshot icon if applicable
 *   b. Melee weapon kill → customui:textures/overlay/melee.png
 *   c. Superbwarfare gun kill → SBW gun icon
 *   d. Other kills → customui:textures/overlay/generic.png
 */
@OnlyIn(Dist.CLIENT)
public class KillMessageOverlayRenderer implements IGuiOverlay {

    private static final Logger LOGGER = LogManager.getLogger("CustomUI-KillMessage");
    private static final KillMessageOverlayRenderer INSTANCE = new KillMessageOverlayRenderer();

    // Pre-allocated static ResourceLocation for headshot icon — avoids per-frame allocation
    private static final ResourceLocation HEADSHOT_ICON = ResourceLocation.tryParse("customui:textures/overlay/headshot.png");
    private static final ResourceLocation GENERIC_ICON = ResourceLocation.tryParse("customui:textures/overlay/generic.png");
    private static final ResourceLocation MELEE_ICON = ResourceLocation.tryParse("customui:textures/overlay/melee.png");

    // Cache for icon dimensions: ResourceLocation → {width, height}
    // Avoids re-decoding the same PNG every time a kill with the same weapon appears
    private static final Map<ResourceLocation, int[]> ICON_DIMENSION_CACHE = new ConcurrentHashMap<>();

    // Constants
    private static final int MAX_MESSAGES = 6;
    private static final float ENTRY_HEIGHT = 14f;
    private static final float ENTRY_SPACING = 4f;
    private static final float BASE_OFFSET_Y = 18f;
    private static final float MARGIN_Y = 10f;

    // Colors
    private static final int COLOR_TEAMMATE = 0xFF55FF55; // Green
    private static final int COLOR_ENEMY = 0xFFFF5555;    // Red
    private static final int COLOR_FLASH_RED = 0xFFFF4444;  // Bright red flash for teammate death
    private static final int COLOR_FLASH_GREEN = 0xFF55FF55; // Bright green flash for enemy death
    private static final int BG_COLOR = 0x80202020;       // Dark semi-transparent background
    private static final int LOCAL_DEATH_BG_RGB = 0xC80808;
    private static final int LOCAL_KILL_BORDER_RGB = 0xFF4444;
    private static final int LOCAL_DEATH_BORDER_RGB = 0x9A9A9A;
    private static final int KILLER_STRIKE_RGB = 0x9B9B9B;

    // Kill messages
    private final List<KillMessage> messages = new ArrayList<>();
    private long lastUpdateTimeNanos = -1;

    private static boolean resourceExists(ResourceLocation location) {
        return RenderResourceCache.exists(location);
    }

    static int backgroundRgb(boolean victimIsLocalPlayer) {
        return victimIsLocalPlayer ? LOCAL_DEATH_BG_RGB : (BG_COLOR & 0x00FFFFFF);
    }

    private static IconResolution resolveIconResource(String weaponIconPath, byte iconType) {
        ResourceLocation parsed = weaponIconPath == null || weaponIconPath.isBlank()
                ? null
                : ResourceLocation.tryParse(weaponIconPath);
        if (resourceExists(parsed)) {
            return new IconResolution(parsed, iconType == 0 || iconType == 1);
        }

        ResourceLocation fallback = iconType == 2 ? MELEE_ICON : GENERIC_ICON;
        if (resourceExists(fallback)) {
            return new IconResolution(fallback, false);
        }

        return new IconResolution(parsed, iconType == 0 || iconType == 1);
    }


    public static KillMessageOverlayRenderer getInstance() {
        return INSTANCE;
    }

    public void clearMessages() {
        messages.clear();
        lastUpdateTimeNanos = -1;
        // Note: ICON_DIMENSION_CACHE is intentionally NOT cleared here.
        // Icon dimensions are intrinsic to the texture and do not change
        // when messages are toggled on/off.
    }

    /**
     * Add a new kill message.
     *
     * @param killerName Empty string if no killer (fall damage, self-kill, unknown)
     * @param victimName Name of the victim
     * @param weaponIconPath Path to weapon/damage icon
     * @param killerIsTeammate Whether killer is on same team as local player
     * @param victimIsTeammate Whether victim is on same team as local player
     * @param isHeadshot Whether it was a headshot
     * @param killerIsLocalPlayer Whether the killer is the local player
     * @param victimIsLocalPlayer Whether the victim is the local player
     */
    public void addKillMessage(String killerName, String victimName, String weaponIconPath,
                                boolean killerIsTeammate, boolean victimIsTeammate, boolean isHeadshot,
                                byte iconType, boolean killerIsLocalPlayer, boolean victimIsLocalPlayer) {
        addKillMessage(killerName, victimName, weaponIconPath, killerIsTeammate, victimIsTeammate, isHeadshot,
                iconType, killerIsLocalPlayer, victimIsLocalPlayer, null, null);
    }

    public void addKillMessage(String killerName, String victimName, String weaponIconPath,
                                boolean killerIsTeammate, boolean victimIsTeammate, boolean isHeadshot,
                                byte iconType, boolean killerIsLocalPlayer, boolean victimIsLocalPlayer,
                                UUID killerUuid, UUID victimUuid) {
        // Don't add if kill messages are disabled
        if (!KillMessageClientState.getInstance().isEnabled()) {
            return;
        }

        // Determine effective teammate status based on display mode
        boolean effectiveKillerIsTeammate;
        boolean effectiveVictimIsTeammate;
        boolean shouldFlash;

        KillMessageClientState.DisplayMode mode = KillMessageClientState.getInstance().getDisplayMode();

        // Debug logging
        LOGGER.debug("[KillMsg] Mode={}, killer={}, victim={}, killerIsLocal={}, victimIsLocal={}, killerIsTeam={}, victimIsTeam={}",
                mode, killerName, victimName, killerIsLocalPlayer, victimIsLocalPlayer, killerIsTeammate, victimIsTeammate);

        if (mode == KillMessageClientState.DisplayMode.ALLY_PLAYER) {
            // AllyPlayer mode: Self is considered ally, all other players are enemies
            // - Green flash when local player kills someone (self is the killer/ally)
            // - Red flash when local player is killed (self/ally is killed by enemy)
            // - No flash for kills between other players (doesn't involve self)

            if (killerIsLocalPlayer) {
                // Local player killed someone -> Green flash (ally killed enemy)
                effectiveKillerIsTeammate = true;
                effectiveVictimIsTeammate = false;
                shouldFlash = true;
            } else if (victimIsLocalPlayer) {
                // Local player was killed -> Red flash (ally was killed)
                effectiveKillerIsTeammate = false;
                effectiveVictimIsTeammate = true;
                shouldFlash = true;
            } else {
                // Other players' kills -> No flash (doesn't involve self)
                effectiveKillerIsTeammate = false;
                effectiveVictimIsTeammate = false;
                shouldFlash = false;
            }
        } else {
            // AllyTeam mode (default): use actual team information
            effectiveKillerIsTeammate = killerIsTeammate;
            effectiveVictimIsTeammate = victimIsTeammate;
            // Flash when teammate kills enemy or teammate is killed
            shouldFlash = killerIsTeammate || victimIsTeammate;
        }

        KillMessage msg = new KillMessage(
                killerName, victimName, weaponIconPath,
                effectiveKillerIsTeammate, effectiveVictimIsTeammate, isHeadshot, iconType, shouldFlash,
                killerIsLocalPlayer, victimIsLocalPlayer, killerUuid, victimUuid
        );
        markEliminatedKillers(victimUuid);
        messages.add(0, msg); // Add to front

        // Limit size
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(messages.size() - 1);
        }
    }

    private void markEliminatedKillers(UUID victimUuid) {
        if (victimUuid == null) {
            return;
        }
        for (KillMessage message : messages) {
            if (victimUuid.equals(message.killerUuid)) {
                message.markKillerEliminated();
            }
        }
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        // This overlay is now merged into ScoreboardOverlayRenderer
        // This method is kept for backward compatibility but does nothing
    }

    /**
     * Render kill messages. Called from ScoreboardOverlayRenderer.
     */
    public void renderKillMessages(GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        // Don't render if kill messages are disabled
        if (!KillMessageClientState.getInstance().isEnabled()) {
            return;
        }

        if (messages.isEmpty()) return;

        // Calculate delta time
        long currentTimeNanos = System.nanoTime();
        float deltaMs;
        if (lastUpdateTimeNanos < 0) {
            deltaMs = 16f;
        } else {
            deltaMs = (currentTimeNanos - lastUpdateTimeNanos) / 1_000_000f;
        }
        lastUpdateTimeNanos = currentTimeNanos;
        deltaMs = Math.max(1f, Math.min(deltaMs, 100f));

        // Update sway - same as ScoreboardOverlayRenderer
        UISwayHelper swayHelper = UISwayHelper.getInstance();
        swayHelper.update(partialTick);
        float swayOffsetX = swayHelper.getOffsetXAdventureOnly();
        float swayOffsetY = swayHelper.getOffsetYAdventureOnly();

        // Update messages
        Iterator<KillMessage> it = messages.iterator();
        while (it.hasNext()) {
            KillMessage msg = it.next();
            msg.update(deltaMs);
            if (msg.isExpired()) {
                it.remove();
            }
        }

        if (messages.isEmpty()) return;

        // Render messages
        Font font = mc.font;
        int rightPadding = HotbarConfig.INSTANCE.rightPadding.get();
        // Match the Adventure Hotbar right edge (rightPadding + EXTRA_LEFT_OFFSET=20)
        float baseX = screenWidth - rightPadding - 20;
        boolean applySway = swayOffsetX != 0f || swayOffsetY != 0f;
        int renderCount = messages.size();
        for (int i = 0; i < renderCount; i++) {
            KillMessage msg = messages.get(i);
            float targetY = BASE_OFFSET_Y + MARGIN_Y + i * (ENTRY_HEIGHT + ENTRY_SPACING);
            float currentY = msg.getCurrentY(targetY, deltaMs);
            RENDER_DATA_BUFFER[i].populate(font, msg, baseX, currentY);
        }

        if (applySway) {
            graphics.pose().pushPose();
            graphics.pose().translate(swayOffsetX, swayOffsetY, 0);
        }

        // Frosted-glass row backdrops (one panel per message), tinted with the
        // original dark background color (BG_COLOR) so the row blends over the
        // blur instead of going fully transparent. Local-death rows keep their
        // solid red styling; fallback keeps the flat dark quad.
        for (int i = 0; i < renderCount; i++) {
            KillMessageRenderData layout = RENDER_DATA_BUFFER[i];
            layout.glassBackdrop = false;
            KillMessage msg = layout.message();
            if (msg == null || msg.victimIsLocalPlayer) continue;
            float alpha = layout.alpha();
            if (AlphaFadeHelper.shouldSkipRender(alpha)) continue;
            layout.glassBackdrop = com.lootmatrix.customui.client.glass.GlassPanelRenderer.drawHudPanel(
                    graphics, layout.boxLeft(), layout.boxTop(),
                    layout.boxRight() - layout.boxLeft(), layout.boxBottom() - layout.boxTop(),
                    2.5f, BG_COLOR, 0x26FFFFFF, AlphaFadeHelper.smoothAlpha(alpha));
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder geomBuf = Tesselator.getInstance().getBuilder();
        geomBuf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = graphics.pose().last().pose();

        for (int i = 0; i < renderCount; i++) {
            addKillMessageGeometry(geomBuf, matrix, RENDER_DATA_BUFFER[i]);
        }
        BufferUploader.drawWithShader(geomBuf.end());

        MultiBufferSource.BufferSource textBuffers = mc.renderBuffers().bufferSource();
        for (int i = 0; i < renderCount; i++) {
            renderKillMessageContent(graphics, font, textBuffers, RENDER_DATA_BUFFER[i]);
        }
        textBuffers.endBatch();
        renderKillerStrikeLines(graphics, renderCount);

        RenderSystem.disableBlend();
        if (applySway) {
            graphics.pose().popPose();
        }
    }

    private void renderKillerStrikeLines(GuiGraphics graphics, int renderCount) {
        boolean hasStrike = false;
        for (int i = 0; i < renderCount; i++) {
            if (RENDER_DATA_BUFFER[i].killerStrikeProgress() > 0f) {
                hasStrike = true;
                break;
            }
        }
        if (!hasStrike) {
            return;
        }

        // Text batch teardown may leave blending disabled; restore it for alpha-faded strike quads.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder strikeBuf = Tesselator.getInstance().getBuilder();
        strikeBuf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = graphics.pose().last().pose();
        for (int i = 0; i < renderCount; i++) {
            addKillerStrikeLine(strikeBuf, matrix, RENDER_DATA_BUFFER[i]);
        }
        BufferUploader.drawWithShader(strikeBuf.end());
    }

    private void addKillerStrikeLine(BufferBuilder buf, Matrix4f matrix, KillMessageRenderData layout) {
        float progress = layout.killerStrikeProgress();
        if (progress <= 0f) {
            return;
        }

        float alpha = layout.alpha();
        if (AlphaFadeHelper.shouldSkipRender(alpha)) {
            return;
        }

        float width = KillMessageVisualEffects.strikeWidth(layout.killerWidth(), progress);
        if (width <= 0f) {
            return;
        }

        int lineAlpha = KillMessageVisualEffects.strikeLineAlphaInt(alpha);
        if (lineAlpha <= 0) {
            return;
        }
        int color = (lineAlpha << 24) | KILLER_STRIKE_RGB;
        float y = layout.killerTextMidY();
        addQuad(buf, matrix, layout.killerLeft(), y - 1.5f, layout.killerLeft() + width, y + 1.5f, color);
    }


    private void addKillMessageGeometry(BufferBuilder buf, Matrix4f matrix, KillMessageRenderData layout) {
        KillMessage msg = layout.message();
        float alpha = layout.alpha();
        if (AlphaFadeHelper.shouldSkipRender(alpha)) return;

        // Flash effect
        float flashProgress = msg.getFlashProgress();
        int flashColor = msg.getFlashColor();

        float smoothedAlpha = AlphaFadeHelper.smoothAlpha(alpha);
        if (!layout.glassBackdrop) {
            int bgAlpha = KillMessageVisualEffects.backgroundAlphaInt(alpha);
            int bgColor = (bgAlpha << 24) | backgroundRgb(msg.victimIsLocalPlayer);
            addQuad(buf, matrix, layout.boxLeft(), layout.boxTop(), layout.boxRight(), layout.boxBottom(), bgColor);
        }

        int borderRgb = msg.victimIsLocalPlayer ? LOCAL_DEATH_BORDER_RGB
                : msg.killerIsLocalPlayer ? LOCAL_KILL_BORDER_RGB : -1;
        if (borderRgb >= 0) {
            int borderAlpha = (int) (smoothedAlpha * 220);
            int borderColor = (borderAlpha << 24) | borderRgb;
            addQuad(buf, matrix, layout.boxLeft() + 1, layout.boxTop(), layout.boxRight() - 1, layout.boxTop() + 1, borderColor);
            addQuad(buf, matrix, layout.boxLeft() + 1, layout.boxBottom() - 1, layout.boxRight() - 1, layout.boxBottom(), borderColor);
            addQuad(buf, matrix, layout.boxLeft(), layout.boxTop(), layout.boxLeft() + 1, layout.boxBottom(), borderColor);
            addQuad(buf, matrix, layout.boxRight() - 1, layout.boxTop(), layout.boxRight(), layout.boxBottom(), borderColor);
        }

        if (flashProgress > 0) {
            int flashAlpha = (int) (flashProgress * alpha * 240);
            int flashBg = (flashAlpha << 24) | (flashColor & 0x00FFFFFF);
            addQuad(buf, matrix, layout.boxLeft(), layout.boxTop(), layout.boxRight(), layout.boxBottom(), flashBg);

            int glowExtend = 20;
            for (int i = 0; i < glowExtend; i++) {
                float t = (float) i / glowExtend;
                int gAlpha = (int) (flashAlpha * (1f - t) * 0.6f);
                if (gAlpha <= 0) break;
                int gc = (gAlpha << 24) | (flashColor & 0x00FFFFFF);
                addQuad(buf, matrix, layout.boxLeft() - i - 1, layout.boxTop(), layout.boxLeft() - i, layout.boxBottom(), gc);
            }
        }
    }

    private void renderKillMessageContent(GuiGraphics graphics, Font font,
                                          MultiBufferSource.BufferSource textBuffers,
                                          KillMessageRenderData layout) {
        KillMessage msg = layout.message();
        float alpha = layout.alpha();
        if (AlphaFadeHelper.shouldSkipRender(alpha)) return;

        // ── 4. Draw content right-to-left ──
        float smoothedAlpha = AlphaFadeHelper.smoothAlpha(alpha);
        int textAlpha = AlphaFadeHelper.clampAlphaInt((int) (smoothedAlpha * 255f));
        float currentX = layout.rightX();

        // Determine name colors based on display mode
        KillMessageClientState.DisplayMode mode = KillMessageClientState.getInstance().getDisplayMode();
        int victimNameColor;
        int killerNameColor;
        if (mode == KillMessageClientState.DisplayMode.ALLY_PLAYER) {
            // AllyPlayer mode: self is green, others are red
            victimNameColor = (textAlpha << 24) | (msg.victimIsLocalPlayer ? (COLOR_TEAMMATE & 0x00FFFFFF) : (COLOR_ENEMY & 0x00FFFFFF));
            killerNameColor = (textAlpha << 24) | (msg.killerIsLocalPlayer ? (COLOR_TEAMMATE & 0x00FFFFFF) : (COLOR_ENEMY & 0x00FFFFFF));
        } else {
            // AllyTeam mode: teammate is green, enemy is red
            victimNameColor = (textAlpha << 24) | (msg.victimIsTeammate ? (COLOR_TEAMMATE & 0x00FFFFFF) : (COLOR_ENEMY & 0x00FFFFFF));
            killerNameColor = (textAlpha << 24) | (msg.killerIsTeammate ? (COLOR_TEAMMATE & 0x00FFFFFF) : (COLOR_ENEMY & 0x00FFFFFF));
        }
        float killerStrikeProgress = layout.killerStrikeProgress();
        if (killerStrikeProgress > 0f) {
            killerNameColor = KillMessageVisualEffects.grayOutNameColor(killerNameColor, killerStrikeProgress);
        }

        // Victim name (rightmost)
        currentX -= layout.victimWidth();
        drawBatchedText(font, textBuffers, graphics.pose().last().pose(),
                msg.victimName, currentX, layout.textTopY(), victimNameColor);

        // Headshot icon (only for TACZ kills) — vertically centered
        if (layout.showHeadshot()) {
            currentX -= layout.spacing() + layout.headshotIconWidth();
            int headshotY = Math.round(layout.boxMidY() - 6f); // 12px icon, center in box
            if (HEADSHOT_ICON != null) {
                RenderSystem.enableBlend();
                RenderSystem.setShaderColor(1f, 1f, 1f, AlphaFadeHelper.safeShaderAlpha(alpha));
                graphics.blit(HEADSHOT_ICON, (int) currentX, headshotY, 0, 0, 12, 12, 12, 12);
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            }
        }

        // Weapon/damage icon — vertically centered, horizontally flipped only for TACZ
        if (msg.weaponIcon != null && layout.iconDisplayWidth() > 0) {
            currentX -= layout.spacing() + layout.iconDisplayWidth();
            int iconY = Math.round(layout.boxMidY() - layout.iconDisplayHeight() * 0.5f);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1f, 1f, 1f, AlphaFadeHelper.safeShaderAlpha(alpha));
            if (msg.flipHorizontally) {
                graphics.blit(msg.weaponIcon, (int) currentX, iconY,
                        0, 0, layout.iconDisplayWidth(), layout.iconDisplayHeight(),
                        -layout.iconDisplayWidth(), layout.iconDisplayHeight());
            } else {
                graphics.blit(msg.weaponIcon, (int) currentX, iconY,
                        0, 0, layout.iconDisplayWidth(), layout.iconDisplayHeight(),
                        layout.iconDisplayWidth(), layout.iconDisplayHeight());
            }
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }

        // Killer name (leftmost)
        if (layout.hasKiller()) {
            currentX -= layout.spacing() + layout.killerWidth();
            drawBatchedText(font, textBuffers, graphics.pose().last().pose(),
                    msg.killerName, currentX, layout.textTopY(), killerNameColor);
        }
    }

    private static void drawBatchedText(Font font, MultiBufferSource.BufferSource textBuffers,
                                        Matrix4f matrix, String text, float x, float y, int color) {
        font.drawInBatch(
                text,
                x,
                y,
                color,
                false,
                matrix,
                textBuffers,
                Font.DisplayMode.NORMAL,
                0,
                15728880
        );
    }

    private static void addQuad(BufferBuilder buf, Matrix4f matrix,
                                float left, float top, float right, float bottom, int color) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        buf.vertex(matrix, left, bottom, 0).color(r, g, b, a).endVertex();
        buf.vertex(matrix, right, bottom, 0).color(r, g, b, a).endVertex();
        buf.vertex(matrix, right, top, 0).color(r, g, b, a).endVertex();
        buf.vertex(matrix, left, top, 0).color(r, g, b, a).endVertex();
    }

    // GC optimization: pre-allocated static buffer for render data — eliminates per-frame array + record allocation
    private static final KillMessageRenderData[] RENDER_DATA_BUFFER = new KillMessageRenderData[MAX_MESSAGES];
    static {
        for (int i = 0; i < MAX_MESSAGES; i++) RENDER_DATA_BUFFER[i] = new KillMessageRenderData();
    }

    private static final class KillMessageRenderData {
        KillMessage message;
        float rightX;
        float topY;
        boolean hasKiller;
        int killerWidth;
        int victimWidth;
        int iconDisplayWidth;
        int iconDisplayHeight;
        boolean showHeadshot;
        int headshotIconWidth;
        int spacing;
        int boxLeft;
        int boxRight;
        float boxTop;
        /** True when a frosted-glass panel replaced the flat dark background this frame. */
        boolean glassBackdrop;
        float boxBottom;
        float boxMidY;
        float killerLeft;
        float killerStrikeProgress;
        float textTopY;
        float killerTextMidY;
        float alpha;

        void populate(Font font, KillMessage msg, float rightX, float topY) {
            this.message = msg;
            this.rightX = rightX;
            this.topY = topY;
            this.alpha = msg.getAlpha();
            this.hasKiller = !msg.killerName.isEmpty();
            this.killerWidth = this.hasKiller ? font.width(msg.killerName) : 0;
            this.victimWidth = font.width(msg.victimName);
            this.iconDisplayWidth = msg.weaponIcon != null ? msg.iconDisplayWidth : 0;
            this.iconDisplayHeight = msg.weaponIcon != null ? msg.iconDisplayHeight : 0;
            this.showHeadshot = msg.isHeadshot && msg.iconType == 0;
            this.headshotIconWidth = this.showHeadshot ? 12 : 0;
            this.spacing = 4;
            int contentWidth = this.victimWidth;
            if (this.headshotIconWidth > 0) contentWidth += this.headshotIconWidth + this.spacing;
            if (this.iconDisplayWidth > 0) contentWidth += this.iconDisplayWidth + this.spacing;
            if (this.hasKiller) contentWidth += this.killerWidth + this.spacing;
            this.boxRight = (int) rightX + 4;
            this.boxLeft = (int) rightX - contentWidth - 4;
            this.boxTop = topY - 2f;
            this.boxBottom = topY + 12f;
            this.boxMidY = (this.boxTop + this.boxBottom) * 0.5f;
            this.killerLeft = this.hasKiller ? rightX - contentWidth : rightX;
            this.killerStrikeProgress = this.hasKiller ? msg.getKillerEliminationProgress() : 0f;
            this.textTopY = KillMessageVisualEffects.centeredTextTopY(this.boxTop, this.boxBottom, font.lineHeight);
            this.killerTextMidY = KillMessageVisualEffects.strikeCenterY(this.textTopY, font.lineHeight);
        }

        KillMessage message() { return message; }
        float rightX() { return rightX; }
        float topY() { return topY; }
        boolean hasKiller() { return hasKiller; }
        int killerWidth() { return killerWidth; }
        int victimWidth() { return victimWidth; }
        int iconDisplayWidth() { return iconDisplayWidth; }
        int iconDisplayHeight() { return iconDisplayHeight; }
        boolean showHeadshot() { return showHeadshot; }
        int headshotIconWidth() { return headshotIconWidth; }
        int spacing() { return spacing; }
        int boxLeft() { return boxLeft; }
        int boxRight() { return boxRight; }
        float boxTop() { return boxTop; }
        float boxBottom() { return boxBottom; }
        float boxMidY() { return boxMidY; }
        float killerLeft() { return killerLeft; }
        float killerStrikeProgress() { return killerStrikeProgress; }
        float textTopY() { return textTopY; }
        float killerTextMidY() { return killerTextMidY; }
        float alpha() { return alpha; }
    }

    /**
     * Inner class representing a kill message entry.
     */
    private static class KillMessage {
        final String killerName;
        final String victimName;
        final ResourceLocation weaponIcon;
        final boolean flipHorizontally;
        final boolean killerIsTeammate;
        final boolean victimIsTeammate;
        final boolean isHeadshot;
        final byte iconType; // 0=TACZ, 1=SBW, 2=melee, 3=other
        final boolean shouldFlash; // Whether this message should show flash effect
        final boolean killerIsLocalPlayer; // For AllyPlayer mode: killer is local player
        final boolean victimIsLocalPlayer; // For AllyPlayer mode: victim is local player
        final UUID killerUuid;
        final UUID victimUuid;
        // Icon display size scaled to original aspect ratio
        final int iconDisplayWidth;
        final int iconDisplayHeight;

        float timerMs = 0f;
        float killerEliminationTimerMs = -1f;
        float displayY = -20f;
        boolean firstUpdate = true;

        private static final int ICON_MAX_HEIGHT = 8;
        private static final int[] DEFAULT_ICON_DIMS = new int[]{32, 8};

        KillMessage(String killerName, String victimName, String weaponIconPath,
                    boolean killerIsTeammate, boolean victimIsTeammate, boolean isHeadshot,
                    byte iconType, boolean shouldFlash, boolean killerIsLocalPlayer, boolean victimIsLocalPlayer,
                    UUID killerUuid, UUID victimUuid) {
            this.killerName = killerName != null ? killerName : "";
            this.victimName = victimName;
            IconResolution iconResolution = resolveIconResource(weaponIconPath, iconType);
            this.weaponIcon = iconResolution.location;
            this.flipHorizontally = iconResolution.flipHorizontally;
            this.killerIsTeammate = killerIsTeammate;
            this.victimIsTeammate = victimIsTeammate;
            this.isHeadshot = isHeadshot;
            this.iconType = iconType;
            this.shouldFlash = shouldFlash;
            this.killerIsLocalPlayer = killerIsLocalPlayer;
            this.victimIsLocalPlayer = victimIsLocalPlayer;
            this.killerUuid = killerUuid;
            this.victimUuid = victimUuid;

            // Compute display dimensions from texture aspect ratio
            if (this.weaponIcon != null) {
                int[] dims = loadIconDimensions(this.weaponIcon);
                int texW = dims[0];
                int texH = dims[1];
                if (texH > 0 && texW > 0) {
                    float aspect = (float) texW / texH;
                    this.iconDisplayHeight = ICON_MAX_HEIGHT;
                    this.iconDisplayWidth = Math.max(1, Math.round(ICON_MAX_HEIGHT * aspect));
                } else {
                    this.iconDisplayHeight = ICON_MAX_HEIGHT;
                    this.iconDisplayWidth = 32; // fallback
                }
            } else {
                this.iconDisplayHeight = 0;
                this.iconDisplayWidth = 0;
            }
        }

        /**
         * Get the pixel dimensions of a texture, using a global cache to avoid
         * re-reading the PNG header every time a kill with the same weapon appears.
         * <p>
         * Uses a lightweight PNG IHDR header read (24 bytes) instead of
         * {@code javax.imageio.ImageIO.read()} to avoid:
         * <ul>
         *   <li>Full pixel-data decode of the entire PNG</li>
         *   <li>First-call lazy initialization of the javax.imageio subsystem
         *       (class loading + ServiceProvider scanning = 100–500 ms)</li>
         *   <li>Heavyweight {@code BufferedImage} allocation + GC pressure</li>
         * </ul>
         * A valid PNG always starts with an 8-byte signature followed by the
         * IHDR chunk whose data (at file offsets 16–23) contains width (4 bytes)
         * and height (4 bytes) in big-endian order.
         * <p>
         * Fallback to 32×8 if the texture cannot be read.
         */
        private static int[] loadIconDimensions(ResourceLocation loc) {
            int[] cached = ICON_DIMENSION_CACHE.get(loc);
            if (cached != null) {
                return cached;
            }

            int[] dims = DEFAULT_ICON_DIMS;
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && RenderResourceCache.exists(loc)) {
                    var resource = mc.getResourceManager().getResource(loc);
                    if (resource.isPresent()) {
                        try (var stream = resource.get().open()) {
                            // PNG layout: 8-byte signature, then IHDR chunk:
                            //   [4 len][4 'IHDR'][4 width][4 height][...]
                            // Width is at offset 16, height at offset 20 (big-endian).
                            byte[] header = new byte[24];
                            int bytesRead = 0;
                            while (bytesRead < 24) {
                                int n = stream.read(header, bytesRead, 24 - bytesRead);
                                if (n < 0) break;
                                bytesRead += n;
                            }
                            if (bytesRead == 24) {
                                int w = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                                      | ((header[18] & 0xFF) << 8)  |  (header[19] & 0xFF);
                                int h = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                                      | ((header[22] & 0xFF) << 8)  |  (header[23] & 0xFF);
                                if (w > 0 && h > 0) {
                                    dims = new int[]{w, h};
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            ICON_DIMENSION_CACHE.put(loc, dims);
            return dims;
        }

        void update(float deltaMs) {
            timerMs += deltaMs;
            if (killerEliminationTimerMs >= 0f) {
                killerEliminationTimerMs += deltaMs;
            }
        }

        void markKillerEliminated() {
            if (killerUuid != null && killerEliminationTimerMs < 0f) {
                killerEliminationTimerMs = 0f;
            }
        }

        float getKillerEliminationProgress() {
            return killerEliminationTimerMs >= 0f
                    ? KillMessageVisualEffects.crossoutProgress(killerEliminationTimerMs)
                    : 0f;
        }

        boolean isExpired() {
            return KillMessageVisualEffects.messageExpired(timerMs);
        }

        float getAlpha() {
            return KillMessageVisualEffects.messageAlpha(timerMs);
        }


        float getFlashProgress() {
            // No flash if shouldFlash is false (e.g., kills between other players in AllyPlayer mode)
            if (!shouldFlash) {
                return 0f;
            }
            // Flash in first 300ms - for both teammate and enemy death
            if (timerMs < KillMessageVisualEffects.FLASH_DURATION_MS) {
                float t = timerMs / KillMessageVisualEffects.FLASH_DURATION_MS;
                return 1f - t;
            }
            return 0f;
        }

        int getFlashColor() {
            // Red flash for teammate death, green flash for enemy death
            return victimIsTeammate ? COLOR_FLASH_RED : COLOR_FLASH_GREEN;
        }

        float getCurrentY(float targetY, float deltaMs) {
            if (firstUpdate) {
                displayY = targetY;
                firstUpdate = false;
                return targetY;
            }

            // Smooth position animation
            float lerp = Math.min(1f, deltaMs * 0.015f);
            displayY = Mth.lerp(lerp, displayY, targetY);
            return displayY;
        }
    }

    private record IconResolution(ResourceLocation location, boolean flipHorizontally) {}
}
