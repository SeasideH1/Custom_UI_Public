package com.lootmatrix.customui.client.glass;

import com.lootmatrix.customui.Main;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlConst;
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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * Shared frosted-glass blur source: once per frame (on first demand) the main
 * framebuffer is downsampled to quarter resolution and gaussian-blurred in two
 * separable passes. Every glass widget then samples the same blurred texture,
 * so the per-frame cost is fixed (3 small fullscreen passes) no matter how
 * many widgets are on screen.
 *
 * Render targets are reused and only recreated when the window size changes
 * (signature-based dirty check, no per-frame allocation).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class GlassBlurPipeline {

    private static final int DOWNSCALE = 4;

    @Nullable private static TextureTarget targetA;
    @Nullable private static TextureTarget targetB;
    private static int lastWidth = -1;
    private static int lastHeight = -1;

    /** Pre-HUD snapshot: the pure world image (in-world HUD panels). */
    public static final int PHASE_WORLD = 0;
    /** Mid-screen snapshot: includes the menu's own backdrop layers (widgets). */
    public static final int PHASE_SCREEN = 1;

    private static long frameId;
    private static long preparedFrame = -1;
    private static int preparedPhase = -1;
    private static boolean preparedResult;
    /** Last frame on which any HUD glass consumer drew (drives the pre-HUD blur). */
    private static long lastNeededFrame = Long.MIN_VALUE;

    private GlassBlurPipeline() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            frameId++;
        }
    }

    /** HUD consumers call this every time they draw, so the next frame pre-blurs. */
    public static void markNeeded() {
        lastNeededFrame = frameId;
    }

    /**
     * In-game HUD glass: blur once before any overlay renders, so panels
     * sample the pure world image and no GUI batch is interrupted mid-frame.
     * Runs only on frames following recent HUD glass usage.
     */
    @SubscribeEvent
    public static void onRenderGuiPre(net.minecraftforge.client.event.RenderGuiEvent.Pre event) {
        if (Minecraft.getInstance().level == null) return;
        if (frameId - lastNeededFrame > 2) return;
        prepare(event.getGuiGraphics(), PHASE_WORLD);
    }

    /**
     * Blurs the framebuffer content drawn so far. Phases are monotonic within
     * a frame: a {@code PHASE_SCREEN} request after a {@code PHASE_WORLD}
     * snapshot re-blurs (menu widgets must see the menu backdrop at their own
     * position, not the raw 3D world); equal-or-lower requests reuse the
     * existing result. Returns false when the shaders are unavailable.
     */
    public static boolean prepare(GuiGraphics graphics, int phase) {
        if (preparedFrame == frameId && preparedPhase >= phase) {
            return preparedResult;
        }
        preparedFrame = frameId;
        preparedPhase = phase;
        preparedResult = false;

        ShaderInstance blurShader = GlassShaders.blur();
        if (blurShader == null || GlassShaders.panel() == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        int width = Math.max(1, main.width / DOWNSCALE);
        int height = Math.max(1, main.height / DOWNSCALE);
        if (targetA == null || width != lastWidth || height != lastHeight) {
            destroyTargets();
            targetA = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            targetB = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            targetA.setFilterMode(GlConst.GL_LINEAR);
            targetB.setFilterMode(GlConst.GL_LINEAR);
            lastWidth = width;
            lastHeight = height;
        }

        // Commit pending GUI batches so the blur sees everything drawn so far
        graphics.flush();

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        blurPass(blurShader, main.getColorTextureId(), targetA, 0f, 0f);
        blurPass(blurShader, targetA.getColorTextureId(), targetB, 1f / width, 0f);
        blurPass(blurShader, targetB.getColorTextureId(), targetA, 0f, 1f / height);

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        main.bindWrite(true);

        preparedResult = true;
        return true;
    }

    /** Texture id of the blurred quarter-resolution backdrop. */
    public static int outputTexture() {
        return targetA != null ? targetA.getColorTextureId() : 0;
    }

    private static void blurPass(ShaderInstance shader, int sourceTexture,
                                 RenderTarget destination, float dirX, float dirY) {
        destination.bindWrite(true);
        shader.safeGetUniform("BlurDir").set(dirX, dirY);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, sourceTexture);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(-1f, -1f, 0f).uv(0f, 0f).endVertex();
        builder.vertex(1f, -1f, 0f).uv(1f, 0f).endVertex();
        builder.vertex(1f, 1f, 0f).uv(1f, 1f).endVertex();
        builder.vertex(-1f, 1f, 0f).uv(0f, 1f).endVertex();
        BufferUploader.drawWithShader(builder.end());
    }

    private static void destroyTargets() {
        if (targetA != null) {
            targetA.destroyBuffers();
            targetA = null;
        }
        if (targetB != null) {
            targetB.destroyBuffers();
            targetB = null;
        }
    }
}
