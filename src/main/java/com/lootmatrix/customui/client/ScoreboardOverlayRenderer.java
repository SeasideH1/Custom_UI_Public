package com.lootmatrix.customui.client;

import com.lootmatrix.customui.config.ScoreboardOverlayConfig;
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
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.joml.Matrix4f;

/**
 * Scoreboard overlay renderer.
 * Layout: [TeamA bar | TeamA score | TeamA icon | MM:SS timer | TeamB icon | TeamB score | TeamB bar]
 * Features: sway animation, smooth progress interpolation, wave/ripple glow effects.
 * Timer colon is centered at screen top center.
 */
public class ScoreboardOverlayRenderer implements IGuiOverlay {

    private static final ScoreboardOverlayRenderer INSTANCE = new ScoreboardOverlayRenderer();
    private static final String TIMER_COLON = ":";
    private static final int FULL_BRIGHT = 15728880;

    public static ScoreboardOverlayRenderer getInstance() {
        return INSTANCE;
    }

    // ==================== GC optimization: cached strings ====================
    private int cachedTimerTicks = -1;
    private String cachedMinutes = "00";
    private String cachedSeconds = "00";
    private int cachedScoreA = -1;
    private int cachedScoreB = -1;
    private String cachedScoreAText = "0";
    private String cachedScoreBText = "0";
    private Font cachedFont;
    private int cachedColonWidth = -1;
    private int cachedScoreFixedWidth = -1;
    private int cachedMinutesWidth = -1;
    private int cachedSecondsWidth = -1;
    private int cachedScoreAWidth = -1;
    private int cachedScoreBWidth = -1;

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        if (BackgroundGuard.shouldSkip()) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // Kill feed remains coupled to the scoreboard entry point for layering consistency.
        KillMessageOverlayRenderer.getInstance().renderKillMessages(graphics, partialTick, screenWidth, screenHeight);

        ScoreboardOverlayClientData data = ScoreboardOverlayClientData.getInstance();
        if (!data.visible) return;

        ScoreboardOverlayConfig config = ScoreboardOverlayConfig.INSTANCE;
        int barWidth = config.barWidth.get();
        int barHeight = config.barHeight.get();
        int iconSize = config.iconSize.get();
        int sectionSpacing = config.sectionSpacing.get();
        int offsetX = config.offsetX.get();
        int offsetY = config.offsetY.get();
        float barBgAlpha = (float) config.barBackgroundAlpha.get().doubleValue();
        float barBorderAlpha = (float) config.barBorderAlpha.get().doubleValue();
        boolean reverseFill = data.reverseFillDirection;

        UISwayHelper swayHelper = UISwayHelper.getInstance();
        swayHelper.update(partialTick);
        float swayOffsetX = swayHelper.getOffsetXAdventureOnly();
        float swayOffsetY = swayHelper.getOffsetYAdventureOnly();

        Font font = mc.font;
        refreshTextCache(font, data);

        int screenCenterX = screenWidth / 2 + offsetX;
        int colonX = screenCenterX - cachedColonWidth / 2;
        int minutesX = colonX - cachedMinutesWidth;
        int secondsX = colonX + cachedColonWidth;

        int elementHeight = Math.max(barHeight, Math.max(iconSize, font.lineHeight));
        int centerY = offsetY + elementHeight / 2;
        int textY = centerY - font.lineHeight / 2;
        int barY = centerY - barHeight / 2;
        int iconY = centerY - iconSize / 2;

        graphics.pose().pushPose();
        graphics.pose().translate(swayOffsetX, swayOffsetY, 0);

        int iconAX = minutesX - sectionSpacing - iconSize;
        int scoreABoxX = iconAX - sectionSpacing - cachedScoreFixedWidth;
        int scoreAX = scoreABoxX + (cachedScoreFixedWidth - cachedScoreAWidth) / 2;
        int barAX = scoreABoxX - sectionSpacing - barWidth;

        int iconBX = secondsX + cachedSecondsWidth + sectionSpacing;
        int scoreBBoxX = iconBX + iconSize + sectionSpacing;
        int scoreBX = scoreBBoxX + (cachedScoreFixedWidth - cachedScoreBWidth) / 2;
        int barBX = scoreBBoxX + cachedScoreFixedWidth + sectionSpacing;

        Matrix4f matrix = graphics.pose().last().pose();
        BarFillRegion teamAFill = null;
        BarFillRegion teamBFill = null;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder baseBuffer = Tesselator.getInstance().getBuilder();
        baseBuffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        teamAFill = appendProgressBarBase(
                baseBuffer, matrix, barAX, barY, barWidth, barHeight,
                data.displayedTeamAProgress, data.teamABarColor, !reverseFill,
                barBgAlpha, barBorderAlpha
        );
        teamBFill = appendProgressBarBase(
                baseBuffer, matrix, barBX, barY, barWidth, barHeight,
                data.displayedTeamBProgress, data.teamBBarColor, reverseFill,
                barBgAlpha, barBorderAlpha
        );

        if (data.teamAIcon == null) {
            appendPlaceholderIcon(baseBuffer, matrix, iconAX, iconY, iconSize, data.teamABarColor);
        }
        if (data.teamBIcon == null) {
            appendPlaceholderIcon(baseBuffer, matrix, iconBX, iconY, iconSize, data.teamBBarColor);
        }

        float teamAChangeGlow = data.getChangeGlowIntensity(true);
        float teamBChangeGlow = data.getChangeGlowIntensity(false);
        if (teamAFill != null && teamAFill.isVisible() && teamAChangeGlow > 0.01f
                || teamBFill != null && teamBFill.isVisible() && teamBChangeGlow > 0.01f) {
            if (teamAFill != null && teamAFill.isVisible() && teamAChangeGlow > 0.01f) {
                appendChangeGlowEffect(baseBuffer, matrix, teamAFill, teamAChangeGlow);
            }
            if (teamBFill != null && teamBFill.isVisible() && teamBChangeGlow > 0.01f) {
                appendChangeGlowEffect(baseBuffer, matrix, teamBFill, teamBChangeGlow);
            }
        }

        float teamALeading = data.getLeadingPosition(true);
        float teamBLeading = data.getLeadingPosition(false);
        if (teamAFill != null && teamAFill.isVisible() && teamALeading >= 0f && teamALeading <= 1f
                || teamBFill != null && teamBFill.isVisible() && teamBLeading >= 0f && teamBLeading <= 1f) {
            if (teamAFill != null && teamAFill.isVisible() && teamALeading >= 0f && teamALeading <= 1f) {
                appendLeadingBarEffect(baseBuffer, matrix, teamAFill, teamALeading, !reverseFill);
            }
            if (teamBFill != null && teamBFill.isVisible() && teamBLeading >= 0f && teamBLeading <= 1f) {
                appendLeadingBarEffect(baseBuffer, matrix, teamBFill, teamBLeading, reverseFill);
            }
        }

        BufferUploader.drawWithShader(baseBuffer.end());

        if (data.teamAIcon != null) {
            graphics.blit(data.teamAIcon, iconAX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
        }
        if (data.teamBIcon != null) {
            graphics.blit(data.teamBIcon, iconBX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
        }

        MultiBufferSource.BufferSource textBuffers = mc.renderBuffers().bufferSource();
        int timerColor = data.getEffectiveTimerColor();
        drawBatchedText(font, textBuffers, matrix, cachedScoreAText, scoreAX, textY, 0xFFFFFFFF, true);
        drawBatchedText(font, textBuffers, matrix, cachedMinutes, minutesX, textY, timerColor, true);
        drawBatchedText(font, textBuffers, matrix, TIMER_COLON, colonX, textY, timerColor, true);
        drawBatchedText(font, textBuffers, matrix, cachedSeconds, secondsX, textY, timerColor, true);
        drawBatchedText(font, textBuffers, matrix, cachedScoreBText, scoreBX, textY, 0xFFFFFFFF, true);
        textBuffers.endBatch();

        graphics.pose().popPose();
        RenderSystem.disableBlend();
    }

    private void refreshTextCache(Font font, ScoreboardOverlayClientData data) {
        if (font != cachedFont) {
            cachedFont = font;
            cachedColonWidth = font.width(TIMER_COLON);
            cachedScoreFixedWidth = font.width("0000");
            cachedMinutesWidth = font.width(cachedMinutes);
            cachedSecondsWidth = font.width(cachedSeconds);
            cachedScoreAWidth = font.width(cachedScoreAText);
            cachedScoreBWidth = font.width(cachedScoreBText);
        }

        if (data.timerTicks != cachedTimerTicks) {
            cachedTimerTicks = data.timerTicks;
            cachedMinutes = formatMinutes(data.timerTicks);
            cachedSeconds = formatSeconds(data.timerTicks);
            cachedMinutesWidth = font.width(cachedMinutes);
            cachedSecondsWidth = font.width(cachedSeconds);
        }
        if (data.teamAScore != cachedScoreA) {
            cachedScoreA = data.teamAScore;
            cachedScoreAText = String.valueOf(data.teamAScore);
            cachedScoreAWidth = font.width(cachedScoreAText);
        }
        if (data.teamBScore != cachedScoreB) {
            cachedScoreB = data.teamBScore;
            cachedScoreBText = String.valueOf(data.teamBScore);
            cachedScoreBWidth = font.width(cachedScoreBText);
        }
    }

    private BarFillRegion appendProgressBarBase(BufferBuilder buffer, Matrix4f matrix,
                                                int x, int y, int width, int height,
                                                float progress, int color, boolean rtl,
                                                float bgAlpha, float borderAlpha) {
        progress = Math.max(0f, Math.min(1f, progress));

        int bgColor = ((int) (bgAlpha * 255) << 24) | 0x222222;
        addQuad(buffer, matrix, x + 1, y + 1, x + width - 1, y + height - 1, bgColor);

        int borderColor = ((int) (borderAlpha * 255) << 24) | 0x888888;
        addQuad(buffer, matrix, x + 1, y, x + width - 1, y + 1, borderColor);
        addQuad(buffer, matrix, x + 1, y + height - 1, x + width - 1, y + height, borderColor);
        addQuad(buffer, matrix, x, y, x + 1, y + height, borderColor);
        addQuad(buffer, matrix, x + width - 1, y, x + width, y + height, borderColor);

        int fillWidth = Math.round((width - 2) * progress);
        if (fillWidth <= 0) {
            return null;
        }

        int fillX = rtl ? x + 1 + (width - 2 - fillWidth) : x + 1;
        int fillY = y + 1;
        int fillHeight = height - 2;
        int fillColor = 0xFF000000 | (color & 0x00FFFFFF);
        addQuad(buffer, matrix, fillX, fillY, fillX + fillWidth, fillY + fillHeight, fillColor);
        return new BarFillRegion(fillX, fillY, fillWidth, fillHeight, fillColor);
    }

    private void appendChangeGlowEffect(BufferBuilder buffer, Matrix4f matrix,
                                        BarFillRegion fillRegion, float intensity) {
        int alpha = (int) (Math.max(0f, Math.min(1f, intensity)) * 200);
        if (alpha <= 0) {
            return;
        }
        addQuad(buffer, matrix, fillRegion.x, fillRegion.y,
                fillRegion.x + fillRegion.width, fillRegion.y + fillRegion.height,
                (alpha << 24) | 0x00FFFFFF);
    }

    private void appendLeadingBarEffect(BufferBuilder buffer, Matrix4f matrix,
                                        BarFillRegion fillRegion, float position, boolean rtl) {
        int baseColor = fillRegion.fillColor;
        int brightR = Math.min(255, ((baseColor >> 16) & 0xFF) + 180);
        int brightG = Math.min(255, ((baseColor >> 8) & 0xFF) + 180);
        int brightB = Math.min(255, (baseColor & 0xFF) + 180);

        float barWidth = Math.max(6f, fillRegion.width * 0.1f);
        float glowWidth = Math.max(12f, fillRegion.width * 0.2f);
        float barCenterX = rtl
                ? fillRegion.x + fillRegion.width - (position * fillRegion.width)
                : fillRegion.x + (position * fillRegion.width);

        float glowLeft = Math.max(fillRegion.x, barCenterX - glowWidth);
        float glowRight = Math.min(fillRegion.x + fillRegion.width, barCenterX + glowWidth);
        float barLeft = Math.max(fillRegion.x, barCenterX - barWidth / 2f);
        float barRight = Math.min(fillRegion.x + fillRegion.width, barCenterX + barWidth / 2f);
        float top = fillRegion.y;
        float bottom = fillRegion.y + fillRegion.height;

        int transparentBright = (brightR << 16) | (brightG << 8) | brightB;
        int glowBright = (80 << 24) | (brightR << 16) | (brightG << 8) | brightB;
        int centerBright = (230 << 24) | 0x00FFFFFF;

        if (barLeft > glowLeft) {
            addHorizontalGradientQuad(buffer, matrix, glowLeft, top, barLeft, bottom, transparentBright, glowBright);
        }
        if (glowRight > barRight) {
            addHorizontalGradientQuad(buffer, matrix, barRight, top, glowRight, bottom, glowBright, transparentBright);
        }
        if (barRight > barLeft) {
            float centerX = (barLeft + barRight) * 0.5f;
            if (centerX > barLeft) {
                addHorizontalGradientQuad(buffer, matrix, barLeft, top, centerX, bottom, glowBright, centerBright);
            }
            if (barRight > centerX) {
                addHorizontalGradientQuad(buffer, matrix, centerX, top, barRight, bottom, centerBright, glowBright);
            }
        }
    }

    private void appendPlaceholderIcon(BufferBuilder buffer, Matrix4f matrix,
                                       int x, int y, int size, int color) {
        int c = 0xFF000000 | (color & 0x00FFFFFF);
        int cx = x + size / 2;
        int cy = y + size / 2;
        int radius = size / 3;
        addQuad(buffer, matrix, cx - radius, cy - radius, cx + radius, cy + radius, c);
    }

    private static void addQuad(BufferBuilder buffer, Matrix4f matrix,
                                float left, float top, float right, float bottom, int color) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        buffer.vertex(matrix, left, bottom, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, right, bottom, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, right, top, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, left, top, 0).color(r, g, b, a).endVertex();
    }

    private static void addHorizontalGradientQuad(BufferBuilder buffer, Matrix4f matrix,
                                                  float left, float top, float right, float bottom,
                                                  int leftColor, int rightColor) {
        float leftR = ((leftColor >> 16) & 0xFF) / 255.0f;
        float leftG = ((leftColor >> 8) & 0xFF) / 255.0f;
        float leftB = (leftColor & 0xFF) / 255.0f;
        float leftA = ((leftColor >> 24) & 0xFF) / 255.0f;
        float rightR = ((rightColor >> 16) & 0xFF) / 255.0f;
        float rightG = ((rightColor >> 8) & 0xFF) / 255.0f;
        float rightB = (rightColor & 0xFF) / 255.0f;
        float rightA = ((rightColor >> 24) & 0xFF) / 255.0f;

        buffer.vertex(matrix, left, bottom, 0).color(leftR, leftG, leftB, leftA).endVertex();
        buffer.vertex(matrix, right, bottom, 0).color(rightR, rightG, rightB, rightA).endVertex();
        buffer.vertex(matrix, right, top, 0).color(rightR, rightG, rightB, rightA).endVertex();
        buffer.vertex(matrix, left, top, 0).color(leftR, leftG, leftB, leftA).endVertex();
    }

    private static void drawBatchedText(Font font, MultiBufferSource.BufferSource textBuffers,
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

    private String formatMinutes(int ticks) {
        if (ticks < 0) ticks = 0;
        return formatTwoDigits((ticks / 20) / 60);
    }

    private String formatSeconds(int ticks) {
        if (ticks < 0) ticks = 0;
        return formatTwoDigits((ticks / 20) % 60);
    }

    private static String formatTwoDigits(int value) {
        if (value >= 0 && value < 10) {
            return "0" + value;
        }
        return Integer.toString(Math.max(0, value));
    }

    private record BarFillRegion(int x, int y, int width, int height, int fillColor) {
        private boolean isVisible() {
            return width > 0 && height > 0;
        }
    }
}
