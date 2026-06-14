package com.lootmatrix.customui.client.title;

import com.lootmatrix.customui.cinematic.CameraPathSampler;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws the baked title scene behind menu widgets.
 *
 * Sets up its own perspective projection and camera transform (no Camera
 * entity exists on the title screen), swaps the vanilla lightmap for a
 * deterministic scene lightmap (the real one holds stale world data on the
 * title screen), draws the static VBOs layer by layer and restores every
 * piece of GUI render state afterwards.
 */
@OnlyIn(Dist.CLIENT)
public final class TitleSceneRenderer {

    private static final float NEAR_PLANE = 0.05f;

    // Reused per-frame objects (zero steady-state allocation)
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f SAVED_PROJECTION = new Matrix4f();
    private static final float[] SAVED_FOG_COLOR = new float[4];

    private static DynamicTexture lightmapTexture;
    private static float lightmapMinBrightness = Float.NaN;

    private TitleSceneRenderer() {}

    /**
     * Renders the scene full-screen. Caller guarantees layers is non-empty.
     * GUI projection / model-view / fog / depth state are restored on exit.
     */
    public static void render(GuiGraphics graphics,
                              List<TitleSceneMesher.SceneLayer> layers,
                              TitleSceneAssets.SceneConfig config,
                              CameraPathSampler.Sample camera) {
        Minecraft minecraft = Minecraft.getInstance();

        // Screen-space sky gradient first, while GUI matrices are still active
        graphics.fillGradient(0, 0, graphics.guiWidth(), graphics.guiHeight(),
                config.skyTopColor, config.skyBottomColor);
        graphics.flush();

        // ---- Save GUI state ----
        SAVED_PROJECTION.set(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        float[] fogColor = RenderSystem.getShaderFogColor();
        SAVED_FOG_COLOR[0] = fogColor[0];
        SAVED_FOG_COLOR[1] = fogColor[1];
        SAVED_FOG_COLOR[2] = fogColor[2];
        SAVED_FOG_COLOR[3] = fogColor[3];

        // ---- 3D camera matrices ----
        float aspect = (float) minecraft.getWindow().getWidth()
                / Math.max(1, minecraft.getWindow().getHeight());
        float farPlane = Math.max(config.fogEnd + 32f, 96f);
        PROJECTION.identity().perspective(
                (float) Math.toRadians(Mth.clamp(camera.fov(), 10f, 140f)), aspect, NEAR_PLANE, farPlane);
        RenderSystem.setProjectionMatrix(PROJECTION, VertexSorting.DISTANCE_TO_ORIGIN);

        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.mulPose(Axis.ZP.rotationDegrees(camera.roll()));
        modelView.mulPose(Axis.XP.rotationDegrees(camera.pitch()));
        modelView.mulPose(Axis.YP.rotationDegrees(camera.yaw() + 180.0f));
        modelView.translate(-camera.position().x, -camera.position().y, -camera.position().z);
        RenderSystem.applyModelViewMatrix();

        // ---- Fog ----
        RenderSystem.setShaderFogStart(config.fogStart);
        RenderSystem.setShaderFogEnd(config.fogEnd);
        RenderSystem.setShaderFogColor(
                ((config.fogColor >> 16) & 0xFF) / 255f,
                ((config.fogColor >> 8) & 0xFF) / 255f,
                (config.fogColor & 0xFF) / 255f,
                1f);
        RenderSystem.setShaderFogShape(com.mojang.blaze3d.shaders.FogShape.SPHERE);

        RenderSystem.clear(com.mojang.blaze3d.platform.GlConst.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        int lightmapId = sceneLightmap(config);
        Matrix4f modelViewMatrix = RenderSystem.getModelViewMatrix();
        for (int i = 0; i < layers.size(); i++) {
            TitleSceneMesher.SceneLayer layer = layers.get(i);
            layer.renderType.setupRenderState();
            // Replace the (stale) vanilla lightmap bound by the render type
            RenderSystem.setShaderTexture(2, lightmapId);
            ShaderInstance shader = RenderSystem.getShader();
            layer.buffer.bind();
            layer.buffer.drawWithShader(modelViewMatrix, PROJECTION, shader);
            VertexBuffer.unbind();
            layer.renderType.clearRenderState();
        }

        // ---- Restore GUI state ----
        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);
        RenderSystem.setShaderFogColor(
                SAVED_FOG_COLOR[0], SAVED_FOG_COLOR[1], SAVED_FOG_COLOR[2], SAVED_FOG_COLOR[3]);
        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(SAVED_PROJECTION, savedSorting);
        // Depth values written by the scene must not z-fight the GUI
        RenderSystem.clear(com.mojang.blaze3d.platform.GlConst.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /**
     * 16x16 lightmap matching the scene theme: warm block-light axis, cool
     * sky-light axis, floor at {@code minBrightness}. Built once, rebuilt only
     * when the brightness floor changes.
     */
    private static int sceneLightmap(TitleSceneAssets.SceneConfig config) {
        if (lightmapTexture == null || lightmapMinBrightness != config.minBrightness) {
            if (lightmapTexture == null) {
                lightmapTexture = new DynamicTexture(16, 16, false);
            }
            NativeImage pixels = lightmapTexture.getPixels();
            if (pixels != null) {
                for (int sky = 0; sky < 16; sky++) {
                    for (int block = 0; block < 16; block++) {
                        float skyF = sky / 15f;
                        float blockF = block / 15f;
                        float base = config.minBrightness;
                        // Cool-tinted skylight, warm-tinted blocklight, max blend
                        float r = Math.max(skyF * 0.96f, blockF * 1.00f);
                        float g = Math.max(skyF * 0.98f, blockF * 0.92f);
                        float b = Math.max(skyF * 1.00f, blockF * 0.78f);
                        int ir = (int) (Mth.clamp(base + (1f - base) * r, 0f, 1f) * 255f);
                        int ig = (int) (Mth.clamp(base + (1f - base) * g, 0f, 1f) * 255f);
                        int ib = (int) (Mth.clamp(base + (1f - base) * b, 0f, 1f) * 255f);
                        // NativeImage packs ABGR
                        pixels.setPixelRGBA(block, sky, 0xFF000000 | (ib << 16) | (ig << 8) | ir);
                    }
                }
                lightmapTexture.upload();
            }
            lightmapTexture.setFilter(true, false);
            lightmapMinBrightness = config.minBrightness;
        }
        return lightmapTexture.getId();
    }

    public static void closeLightmap() {
        if (lightmapTexture != null) {
            lightmapTexture.close();
            lightmapTexture = null;
            lightmapMinBrightness = Float.NaN;
        }
    }
}
