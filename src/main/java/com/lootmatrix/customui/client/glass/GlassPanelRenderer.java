package com.lootmatrix.customui.client.glass;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.AbstractUniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Draws LabyMod-style frosted-glass panels: the quad samples the shared
 * blurred backdrop ({@link GlassBlurPipeline}) in screen space and the
 * fragment shader adds a translucent tint, a thin light border and rounded
 * corners (SDF), all multiplied by an overall panel alpha for fades.
 *
 * Uniform handles are resolved once per shader (re)load instead of per draw
 * (map lookups off the hot path); the per-panel cost is 8 uniform uploads and
 * one 4-vertex draw.
 */
@OnlyIn(Dist.CLIENT)
public final class GlassPanelRenderer {

    // Reused corner vectors for GUI-space -> framebuffer-space transform
    private static final Vector4f CORNER_MIN = new Vector4f();
    private static final Vector4f CORNER_MAX = new Vector4f();

    // Uniform handles cached per shader instance (invalidated on shader reload)
    private static ShaderInstance cachedShader;
    private static AbstractUniform uPanelRect;
    private static AbstractUniform uScreenSize;
    private static AbstractUniform uCornerRadius;
    private static AbstractUniform uBorderWidth;
    private static AbstractUniform uTintColor;
    private static AbstractUniform uBorderColor;
    private static AbstractUniform uPanelAlpha;

    private GlassPanelRenderer() {}

    /**
     * Button-style panel with hover/disabled states. Returns false when the
     * glass pipeline is unavailable so callers can fall back to vanilla skins.
     */
    public static boolean drawPanel(GuiGraphics graphics, int x, int y, int width, int height,
                                    float alpha, boolean hovered, boolean active) {
        int tint;
        int border;
        if (!active) {
            tint = colorArgb(0.30f, 0.35f, 0.38f, 0.42f);
            border = colorArgb(0.12f, 1f, 1f, 1f);
        } else if (hovered) {
            tint = colorArgb(0.22f, 1f, 1f, 1f);
            border = colorArgb(0.55f, 1f, 1f, 1f);
        } else {
            tint = colorArgb(0.10f, 1f, 1f, 1f);
            border = colorArgb(0.30f, 1f, 1f, 1f);
        }
        // Menu widgets sample the in-place backdrop (menu dim + earlier layers),
        // never the raw 3D world snapshot used by HUD panels
        return draw(graphics, x, y, width, height, 3f, tint, border, alpha,
                GlassBlurPipeline.PHASE_SCREEN, false);
    }

    /**
     * HUD panel with explicit styling (hotbar backdrop, health bar, kill
     * message rows). {@code panelAlpha} fades the whole panel (message
     * slide-in/out); tint/border alphas control the overlay strength.
     */
    public static boolean drawHudPanel(GuiGraphics graphics, float x, float y, float width, float height,
                                       float cornerRadius, int tintArgb, int borderArgb, float panelAlpha) {
        return draw(graphics, x, y, width, height, cornerRadius, tintArgb, borderArgb, panelAlpha,
                GlassBlurPipeline.PHASE_WORLD, true);
    }

    private static boolean draw(GuiGraphics graphics, float x, float y, float width, float height,
                                float cornerRadius, int tintArgb, int borderArgb, float panelAlpha,
                                int blurPhase, boolean hudConsumer) {
        if (panelAlpha <= 0.01f) {
            return true; // fully faded: nothing to draw, but not a pipeline failure
        }
        if (hudConsumer) {
            GlassBlurPipeline.markNeeded();
        }
        if (!GlassBlurPipeline.prepare(graphics, blurPhase)) {
            return false;
        }
        ShaderInstance shader = GlassShaders.panel();
        if (shader == null) {
            return false;
        }
        ensureUniformHandles(shader);

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        float guiScale = (float) minecraft.getWindow().getGuiScale();
        Matrix4f pose = graphics.pose().last().pose();

        // GUI rect -> framebuffer pixels (y flipped: gl_FragCoord origin is bottom-left)
        CORNER_MIN.set(x, y, 0f, 1f);
        CORNER_MAX.set(x + width, y + height, 0f, 1f);
        pose.transform(CORNER_MIN);
        pose.transform(CORNER_MAX);
        float fbX0 = Math.min(CORNER_MIN.x(), CORNER_MAX.x()) * guiScale;
        float fbX1 = Math.max(CORNER_MIN.x(), CORNER_MAX.x()) * guiScale;
        float fbY0 = main.height - Math.max(CORNER_MIN.y(), CORNER_MAX.y()) * guiScale;
        float fbY1 = main.height - Math.min(CORNER_MIN.y(), CORNER_MAX.y()) * guiScale;

        uPanelRect.set(fbX0, fbY0, fbX1, fbY1);
        uScreenSize.set((float) main.width, (float) main.height);
        uCornerRadius.set(cornerRadius * guiScale);
        uBorderWidth.set(Math.max(1f, guiScale));
        setColorUniform(uTintColor, tintArgb);
        setColorUniform(uBorderColor, borderArgb);
        uPanelAlpha.set(Math.min(1f, panelAlpha));

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, GlassBlurPipeline.outputTexture());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.vertex(pose, x, y, 0f).endVertex();
        builder.vertex(pose, x, y + height, 0f).endVertex();
        builder.vertex(pose, x + width, y + height, 0f).endVertex();
        builder.vertex(pose, x + width, y, 0f).endVertex();
        BufferUploader.drawWithShader(builder.end());
        return true;
    }

    private static void ensureUniformHandles(ShaderInstance shader) {
        if (shader == cachedShader) {
            return;
        }
        cachedShader = shader;
        uPanelRect = shader.safeGetUniform("PanelRect");
        uScreenSize = shader.safeGetUniform("ScreenSize");
        uCornerRadius = shader.safeGetUniform("CornerRadius");
        uBorderWidth = shader.safeGetUniform("BorderWidth");
        uTintColor = shader.safeGetUniform("TintColor");
        uBorderColor = shader.safeGetUniform("BorderColor");
        uPanelAlpha = shader.safeGetUniform("PanelAlpha");
    }

    private static void setColorUniform(AbstractUniform uniform, int argb) {
        uniform.set(
                ((argb >>> 16) & 0xFF) / 255f,
                ((argb >>> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
    }

    private static int colorArgb(float a, float r, float g, float b) {
        return ((int) (a * 255f) << 24) | ((int) (r * 255f) << 16) | ((int) (g * 255f) << 8) | (int) (b * 255f);
    }
}
