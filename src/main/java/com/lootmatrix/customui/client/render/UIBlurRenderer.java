package com.lootmatrix.customui.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Lightweight UI blur renderer using multi-pass texture-quad downsampling with
 * {@code GL_LINEAR} filtering.  No custom shaders needed — hardware bilinear
 * interpolation provides the blur via repeated downsample/upsample passes.
 * <p>
 * Uses textured quads rendered through MC's shader pipeline instead of
 * {@code glBlitFramebuffer} for maximum driver compatibility.
 * <p>
 * <b>Usage (once per frame, before any blur rects):</b>
 * <pre>
 *   UIBlurRenderer.prepareBlur();
 *   UIBlurRenderer.renderBlurRect(gfx, x, y, w, h, r, g, b, a);
 * </pre>
 */
public class UIBlurRenderer {

    /** Downscale factor from screen resolution to blur FBO size. */
    private static final int DOWNSCALE = 4;

    /** Number of extra half-and-back passes for additional blur. */
    private static final int EXTRA_PASSES = 3;

    private static TextureTarget blurA = null;
    private static TextureTarget blurB = null;
    private static int blurW = 0, blurH = 0;

    /** Nanos timestamp of last successful prepare — used for frame dedup. */
    private static long lastPrepareNanos = 0;

    // ---- GC optimization: reusable Matrix4f and viewport array ----
    private static final Matrix4f reusablePrevProj = new Matrix4f();
    private static final Matrix4f reusableIdentity = new Matrix4f();
    private static final Matrix4f reusableOrtho = new Matrix4f();
    private static final int[] reusableViewport = new int[4];

    // ------------------------------------------------------------------ //

    /**
     * Capture the current world framebuffer and blur it into an off-screen FBO.
     * Safe to call multiple times per frame (8 ms dedup window).
     */
    public static void prepareBlur() {
        long now = System.nanoTime();
        if ((now - lastPrepareNanos) < 8_000_000L) return;       // same frame

        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainRT = mc.getMainRenderTarget();
        if (mainRT == null) return;

        int screenW = mainRT.width;
        int screenH = mainRT.height;
        int bw = Math.max(1, screenW / DOWNSCALE);
        int bh = Math.max(1, screenH / DOWNSCALE);

        // ---- save GL state BEFORE ensureTargets (which may change FBO binding) ----
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, reusableViewport);
        reusablePrevProj.set(RenderSystem.getProjectionMatrix());

        ensureTargets(bw, bh);

        // Save and reset model-view matrix to identity
        PoseStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushPose();
        mvStack.setIdentity();
        RenderSystem.applyModelViewMatrix();

        try {
            reusableIdentity.identity();

            // Pass 0: main screen texture → blurA (4× downscale, bilinear = free blur)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, blurA.frameBufferId);
            GL11.glViewport(0, 0, bw, bh);
            RenderSystem.setProjectionMatrix(
                    reusableOrtho.identity().setOrtho(0, bw, 0, bh, -1, 1),
                    VertexSorting.ORTHOGRAPHIC_Z);

            RenderSystem.setShaderTexture(0, mainRT.getColorTextureId());
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            drawFullscreenQuad(reusableIdentity, bw, bh);

            // Extra passes: ping-pong downsample-to-half then upsample-back
            int halfW = Math.max(1, bw / 2);
            int halfH = Math.max(1, bh / 2);
            for (int i = 0; i < EXTRA_PASSES; i++) {
                // blurA → blurB  (shrink to half)
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, blurB.frameBufferId);
                GL11.glViewport(0, 0, halfW, halfH);
                RenderSystem.setProjectionMatrix(
                        reusableOrtho.identity().setOrtho(0, halfW, 0, halfH, -1, 1),
                        VertexSorting.ORTHOGRAPHIC_Z);
                RenderSystem.setShaderTexture(0, blurA.getColorTextureId());
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                drawFullscreenQuad(reusableIdentity, halfW, halfH);

                // blurB → blurA  (expand back)
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, blurA.frameBufferId);
                GL11.glViewport(0, 0, bw, bh);
                RenderSystem.setProjectionMatrix(
                        reusableOrtho.identity().setOrtho(0, bw, 0, bh, -1, 1),
                        VertexSorting.ORTHOGRAPHIC_Z);
                RenderSystem.setShaderTexture(0, blurB.getColorTextureId());
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                drawFullscreenQuad(reusableIdentity, bw, bh);
            }
        } finally {
            // ---- restore GL state exactly ----
            mvStack.popPose();
            RenderSystem.applyModelViewMatrix();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glViewport(reusableViewport[0], reusableViewport[1], reusableViewport[2], reusableViewport[3]);
            RenderSystem.setProjectionMatrix(reusablePrevProj, VertexSorting.ORTHOGRAPHIC_Z);
        }

        lastPrepareNanos = now;
    }

    /**
     * Draw a fullscreen quad sampling the current shader texture, filling (0,0)→(w,h).
     */
    private static void drawFullscreenQuad(Matrix4f mat, float w, float h) {
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(mat, 0, 0, 0).uv(0, 0).endVertex();
        buf.vertex(mat, w, 0, 0).uv(1, 0).endVertex();
        buf.vertex(mat, w, h, 0).uv(1, 1).endVertex();
        buf.vertex(mat, 0, h, 0).uv(0, 1).endVertex();
        BufferUploader.drawWithShader(buf.end());
    }

    // ------------------------------------------------------------------ //

    /**
     * Render a tinted rectangle of the blurred scene at the given GUI position.
     * {@link #prepareBlur()} must have been called earlier in the same frame.
     *
     * @param tintR  red   multiplier for the blurred texture (0–1)
     * @param tintG  green multiplier
     * @param tintB  blue  multiplier
     * @param tintA  overall opacity
     */
    public static void renderBlurRect(GuiGraphics gfx,
                                       float x, float y, float w, float h,
                                       float tintR, float tintG, float tintB, float tintA) {
        if (blurA == null) return;
        // Reject stale data (no prepare this frame)
        if ((System.nanoTime() - lastPrepareNanos) > 50_000_000L) return;

        Minecraft mc = Minecraft.getInstance();
        float guiW = mc.getWindow().getGuiScaledWidth();
        float guiH = mc.getWindow().getGuiScaledHeight();

        // Map GUI coords → texture UV  (OpenGL: v=0 is bottom of screen)
        float u0 = x / guiW;
        float u1 = (x + w) / guiW;
        float v0 = 1f - (y + h) / guiH;   // bottom of rect
        float v1 = 1f - y / guiH;          // top of rect

        RenderSystem.setShaderTexture(0, blurA.getColorTextureId());
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Matrix4f mat = gfx.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        buf.vertex(mat, x,     y + h, 0).uv(u0, v0).color(tintR, tintG, tintB, tintA).endVertex();
        buf.vertex(mat, x + w, y + h, 0).uv(u1, v0).color(tintR, tintG, tintB, tintA).endVertex();
        buf.vertex(mat, x + w, y,     0).uv(u1, v1).color(tintR, tintG, tintB, tintA).endVertex();
        buf.vertex(mat, x,     y,     0).uv(u0, v1).color(tintR, tintG, tintB, tintA).endVertex();

        BufferUploader.drawWithShader(buf.end());
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ //

    private static void ensureTargets(int w, int h) {
        if (blurA != null && blurW == w && blurH == h) return;

        if (blurA != null) blurA.destroyBuffers();
        if (blurB != null) blurB.destroyBuffers();

        // useDepth=true so blurA/B depth attachment format matches MC's main RenderTarget.
        // The blur path never reads/writes depth itself, but if any external code (TaCZ/GeckoLib,
        // shader mods) blits to/from these FBOs with GL_DEPTH_BUFFER_BIT set, a missing depth
        // attachment would raise GL_INVALID_OPERATION "Depth formats do not match".
        blurA = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        blurB = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        blurA.setFilterMode(GL11.GL_LINEAR);
        blurB.setFilterMode(GL11.GL_LINEAR);

        blurW = w;
        blurH = h;
    }

    /** Release GPU resources.  Called on game shutdown / resource reload. */
    public static void cleanup() {
        if (blurA != null) { blurA.destroyBuffers(); blurA = null; }
        if (blurB != null) { blurB.destroyBuffers(); blurB = null; }
    }
}
