package com.lootmatrix.customui.client;

import com.lootmatrix.customui.client.render.RenderResourceCache;
import com.lootmatrix.customui.hud.HudAnchor;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Renders custom titles.
 * Features:
 * - Titles with fade in/out
 * - Title images with fade in/out
 */
@OnlyIn(Dist.CLIENT)
public class ObjectiveOverlayRenderer implements IGuiOverlay {

    private static final ObjectiveOverlayRenderer INSTANCE = new ObjectiveOverlayRenderer();
    private long lastUpdateTimeNanos = -1;

    public static ObjectiveOverlayRenderer getInstance() {
        return INSTANCE;
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        if (BackgroundGuard.shouldSkip()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ObjectiveOverlayClientData data = ObjectiveOverlayClientData.getInstance();

        long currentTimeNanos = System.nanoTime();
        float deltaTime;
        if (lastUpdateTimeNanos < 0) {
            deltaTime = 0.016f;
        } else {
            deltaTime = (currentTimeNanos - lastUpdateTimeNanos) / 1_000_000_000.0f;
        }
        lastUpdateTimeNanos = currentTimeNanos;
        deltaTime = Math.max(0.001f, Math.min(deltaTime, 0.1f));

        // Tick titles
        data.tickTitles(deltaTime * 1000f);

        // Render titles below objectives
        List<ObjectiveOverlayClientData.TitleDisplay> titles = data.getTitles();
        MultiBufferSource.BufferSource textBuffers = mc.renderBuffers().bufferSource();
        for (ObjectiveOverlayClientData.TitleDisplay title : titles) {
            renderTitle(graphics, mc.font, textBuffers, title, screenWidth, screenHeight);
        }
        textBuffers.endBatch();

        // Render image titles
        List<ObjectiveOverlayClientData.ImageTitleDisplay> imageTitles = data.getImageTitles();
        for (ObjectiveOverlayClientData.ImageTitleDisplay imageTitle : imageTitles) {
            renderImageTitle(graphics, mc, imageTitle, screenWidth, screenHeight);
        }
    }

    /** Legacy vertical base (below the scoreboard overlay) used when no anchor is given. */
    private static final int LEGACY_TITLE_BASE_Y = 90;

    /**
     * Render a title with fade animation.
     * Anchored mode: position = screen anchor point + offset; the origin picks
     * which point of the text box lands on that position. Legacy mode keeps the
     * historic "top-center, horizontally centered text" layout.
     */
    private void renderTitle(GuiGraphics graphics, Font font, MultiBufferSource.BufferSource textBuffers,
                             ObjectiveOverlayClientData.TitleDisplay title,
                             int screenWidth, int screenHeight) {
        float alpha = title.getCurrentAlpha();
        if (AlphaFadeHelper.shouldSkipRender(alpha)) return;

        float x;
        float y;
        float originFx;
        float originFy;
        float lineAdvance = title.line * (font.lineHeight * title.scale + 5);
        if (title.anchorId >= 0) {
            HudAnchor anchor = HudAnchor.byId(title.anchorId);
            HudAnchor origin = title.originId >= 0 ? HudAnchor.byId(title.originId) : HudAnchor.CENTER;
            x = screenWidth * anchor.fx + title.offsetX;
            y = screenHeight * anchor.fy + title.offsetY + lineAdvance;
            originFx = origin.fx;
            originFy = origin.fy;
        } else {
            x = screenWidth / 2f + title.offsetX;
            y = LEGACY_TITLE_BASE_Y + title.offsetY + lineAdvance;
            originFx = 0.5f;
            originFy = 0f;
        }

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(title.scale, title.scale, 1f);

        int textWidth = title.getTextWidth(font);
        int colorWithAlpha = AlphaFadeHelper.toColorWithAlpha(alpha, title.color);
        Matrix4f matrix = graphics.pose().last().pose();
        font.drawInBatch(
                title.text,
                -textWidth * originFx,
                -font.lineHeight * originFy,
                colorWithAlpha,
                true,
                matrix,
                textBuffers,
                Font.DisplayMode.NORMAL,
                0,
                15728880
        );

        graphics.pose().popPose();
    }

    /**
     * Render an image title with fade animation. Anchor/origin semantics match
     * {@link #renderTitle}; legacy mode is horizontally centered, top aligned.
     */
    private void renderImageTitle(GuiGraphics graphics, Minecraft mc,
                                   ObjectiveOverlayClientData.ImageTitleDisplay imageTitle,
                                   int screenWidth, int screenHeight) {
        float alpha = imageTitle.getCurrentAlpha();
        if (AlphaFadeHelper.shouldSkipRender(alpha)) return;

        // Resolve icon path (preset or full path)
        ResourceLocation iconLoc = ScoreboardIconPresets.getInstance().resolveIcon(imageTitle.iconPath);
        if (iconLoc == null) {
            iconLoc = RenderResourceCache.get(imageTitle.iconPath);
        }
        if (iconLoc == null) return;

        int size = imageTitle.size;
        float x;
        float y;
        if (imageTitle.anchorId >= 0) {
            HudAnchor anchor = HudAnchor.byId(imageTitle.anchorId);
            HudAnchor origin = imageTitle.originId >= 0 ? HudAnchor.byId(imageTitle.originId) : HudAnchor.CENTER;
            x = screenWidth * anchor.fx + imageTitle.offsetX - size * origin.fx;
            y = screenHeight * anchor.fy + imageTitle.offsetY - size * origin.fy;
        } else {
            x = screenWidth / 2f + imageTitle.offsetX - size / 2f;
            y = LEGACY_TITLE_BASE_Y + imageTitle.offsetY;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, AlphaFadeHelper.safeShaderAlpha(alpha));

        graphics.blit(iconLoc, (int) x, (int) y, 0, 0, size, size, size, size);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
