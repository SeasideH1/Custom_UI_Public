package com.lootmatrix.customui.atmosphere;

import com.lootmatrix.customui.client.render.RenderResourceCache;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Custom sky renderer for the atmosphere system.
 * Renders gradient sky domes, cubemap skyboxes, sun, moon, and stars.
 * Designed for maximum performance — uses static VBO for stars, immediate mode for dome.
 */
public class CustomSkyRenderer {

    // Vanilla texture locations
    private static final ResourceLocation SUN_LOCATION = RenderResourceCache.getOrCreate("minecraft", "textures/environment/sun.png");
    private static final ResourceLocation MOON_LOCATION = RenderResourceCache.getOrCreate("minecraft", "textures/environment/moon_phases.png");
    private static final float SKY_RADIUS = 512f;
    private static final int SKY_SEGMENTS = 32;

    // Stars data (lazily built)
    private static VertexBuffer starBuffer;
    private static float cachedStarDensity = -1f;
    private static float cachedStarR = -1f, cachedStarG = -1f, cachedStarB = -1f;

    // Color sky dome data (rebuilt only when the gradient changes)
    private static VertexBuffer colorSkyBuffer;
    @Nullable
    private static ColorSkyCacheKey cachedColorSkyKey;

    /**
     * Main entry point — called from the mixin when atmosphere is active.
     */
    public static void render(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick,
                              AtmosphereEngine engine, Runnable setupFog) {
        AtmospherePreset preset = engine.getActivePreset();
        if (preset == null || preset.sky == null) return;

        AtmospherePreset.SkyConfig skyConfig = preset.sky;
        float blend = engine.getBlendFactor(partialTick);

        setupFog.run();
        RenderSystem.depthMask(false);
        try {
            if (skyConfig.type == AtmospherePreset.SkyType.COLOR) {
                renderColorSky(poseStack, projectionMatrix, skyConfig, blend);
            } else if (skyConfig.type == AtmospherePreset.SkyType.CUBEMAP) {
                renderCubemapSky(poseStack, projectionMatrix, skyConfig, blend, partialTick);
            }

            RenderSystem.enableBlend();

            // Celestial bodies
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level != null) {
                float celestialAngle = level.getTimeOfDay(partialTick);

                // Stars (render before sun/moon, visible at night)
                if (preset.stars != null) {
                    renderStars(poseStack, projectionMatrix, preset.stars, blend, level, partialTick, celestialAngle);
                }

                // Sun
                if (preset.sun != null) {
                    renderSun(poseStack, projectionMatrix, preset.sun, blend, celestialAngle);
                }

                // Moon
                if (preset.moon != null) {
                    renderMoon(poseStack, projectionMatrix, preset.moon, blend, celestialAngle, level);
                }
            }
        } finally {
            // Always restore GL state — even if a renderer above threw, leaving
            // depthMask=false would corrupt the rest of the frame's world depth.
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
    }

    // ==================== COLOR Sky ====================

    private static void renderColorSky(PoseStack poseStack, Matrix4f projectionMatrix,
                                        AtmospherePreset.SkyConfig cfg, float blend) {
        // Use the position shader for colored geometry
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) return;
        ensureColorSkyBuffer(cfg);
        if (colorSkyBuffer == null) return;

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        colorSkyBuffer.bind();
        colorSkyBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader);
        VertexBuffer.unbind();
    }

    // ==================== CUBEMAP Sky ====================

    private static void renderCubemapSky(PoseStack poseStack, Matrix4f projectionMatrix,
                                          AtmospherePreset.SkyConfig cfg, float blend,
                                          float partialTick) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.disableCull();

        float S = 500f;
        Matrix4f matrix = poseStack.last().pose();

        // Apply rotation if configured
        if (cfg.cubemapRotationSpeed != 0) {
            float totalAngle = (float) ((System.currentTimeMillis() % 360000L) / 1000.0 * cfg.cubemapRotationSpeed);
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(totalAngle));
            matrix = poseStack.last().pose();
        }

        // Render each face
        renderCubemapFace(matrix, cfg.cubemapUp,    0,  S,  0,  -S, S, -S,  S, S, S,  -S, S, S);   // UP
        renderCubemapFace(matrix, cfg.cubemapDown,   0, -S,  0,  -S,-S, S,  S,-S, S,  S,-S,-S);     // DOWN - fixed winding
        renderCubemapFace(matrix, cfg.cubemapNorth,  0,  0, -S,  -S, S,-S,  S, S,-S,  S,-S,-S);     // NORTH (-Z)
        renderCubemapFace(matrix, cfg.cubemapSouth,  0,  0,  S,   S, S, S, -S, S, S, -S,-S, S);     // SOUTH (+Z)
        renderCubemapFace(matrix, cfg.cubemapEast,   S,  0,  0,   S, S,-S,  S, S, S,  S,-S, S);     // EAST (+X)
        renderCubemapFace(matrix, cfg.cubemapWest,  -S,  0,  0,  -S, S, S, -S, S,-S, -S,-S,-S);     // WEST (-X)

        if (cfg.cubemapRotationSpeed != 0) {
            poseStack.popPose();
        }

        RenderSystem.enableCull();
    }

    private static void renderCubemapFace(Matrix4f matrix, String texturePath,
                                           float nx, float ny, float nz,
                                           float x1, float y1, float z1,
                                           float x2, float y2, float z2,
                                           float x3, float y3, float z3) {
        if (texturePath == null || texturePath.isEmpty()) return;

        ResourceLocation tex = resolveTexture(texturePath, null);
        if (tex == null) return;
        RenderSystem.setShaderTexture(0, tex);

        // Compute 4th vertex (parallelogram)
        float x4 = x1 + (x3 - x2);
        float y4 = y1 + (y3 - y2);
        float z4 = z1 + (z3 - z2);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, x1, y1, z1).uv(0, 0).endVertex();
        builder.vertex(matrix, x2, y2, z2).uv(1, 0).endVertex();
        builder.vertex(matrix, x3, y3, z3).uv(1, 1).endVertex();
        builder.vertex(matrix, x4, y4, z4).uv(0, 1).endVertex();
        tesselator.end();
    }

    // ==================== Stars ====================

    private static void renderStars(PoseStack poseStack, Matrix4f projectionMatrix,
                                     AtmospherePreset.StarsConfig cfg, float blend,
                                     ClientLevel level, float partialTick, float celestialAngle) {
        if (!cfg.visible) return;

        // Calculate star brightness (similar to vanilla)
        float rainLevel = level.getRainLevel(partialTick);
        float starAlpha = cfg.brightness * (1f - rainLevel);
        // In vanilla, stars are brighter at midnight. Use celestial angle for that.
        float nightFactor = Math.max(0, (float) Math.cos(celestialAngle * Math.PI * 2.0));
        starAlpha *= Math.max(0.2f, nightFactor); // Always slightly visible with custom stars

        if (starAlpha <= 0.01f) return;

        // Rebuild star buffer if density or color changed
        if (starBuffer == null || cachedStarDensity != cfg.density
                || cachedStarR != cfg.r || cachedStarG != cfg.g || cachedStarB != cfg.b) {
            buildStarBuffer(cfg.density, cfg.r, cfg.g, cfg.b);
        }

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, starAlpha);

        poseStack.pushPose();
        // Rotate stars with time (subtle rotation like vanilla)
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
        poseStack.mulPose(Axis.XP.rotationDegrees(celestialAngle * 360.0f));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        starBuffer.bind();
        starBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix,
                GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();

        poseStack.popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static void buildStarBuffer(float density, float r, float g, float b) {
        if (starBuffer != null) starBuffer.close();

        int count = (int) (1500 * density);
        Random random = new Random(10842L);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < count; i++) {
            // Random position on unit sphere
            double dx = random.nextFloat() * 2.0 - 1.0;
            double dy = random.nextFloat() * 2.0 - 1.0;
            double dz = random.nextFloat() * 2.0 - 1.0;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 0.01 || dist > 1.0) continue;

            dx /= dist; dy /= dist; dz /= dist;
            float starDist = 100f;
            float cx = (float) (dx * starDist);
            float cy = (float) (dy * starDist);
            float cz = (float) (dz * starDist);

            float size = 0.15f + random.nextFloat() * 0.1f;
            float starR = r * (0.8f + random.nextFloat() * 0.2f);
            float starG = g * (0.8f + random.nextFloat() * 0.2f);
            float starB = b * (0.8f + random.nextFloat() * 0.2f);

            // Build a small quad facing the center
            // Use two perpendicular vectors orthogonal to the radial direction
            double ax, ay, az;
            if (Math.abs(dy) < 0.99) {
                ax = dz; ay = 0; az = -dx;
            } else {
                ax = 1; ay = 0; az = 0;
            }
            double lenA = Math.sqrt(ax * ax + ay * ay + az * az);
            ax /= lenA; ay /= lenA; az /= lenA;
            double bx = dy * az - dz * ay;
            double by = dz * ax - dx * az;
            double bz = dx * ay - dy * ax;

            float sx = (float) (ax * size), sy = (float) (ay * size), sz = (float) (az * size);
            float tx = (float) (bx * size), ty = (float) (by * size), tz = (float) (bz * size);

            builder.vertex(cx - sx - tx, cy - sy - ty, cz - sz - tz)
                    .color(starR, starG, starB, 1f).endVertex();
            builder.vertex(cx + sx - tx, cy + sy - ty, cz + sz - tz)
                    .color(starR, starG, starB, 1f).endVertex();
            builder.vertex(cx + sx + tx, cy + sy + ty, cz + sz + tz)
                    .color(starR, starG, starB, 1f).endVertex();
            builder.vertex(cx - sx + tx, cy - sy + ty, cz - sz + tz)
                    .color(starR, starG, starB, 1f).endVertex();
        }

        BufferBuilder.RenderedBuffer renderedBuffer = builder.end();
        starBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        starBuffer.bind();
        starBuffer.upload(renderedBuffer);
        VertexBuffer.unbind();

        cachedStarDensity = density;
        cachedStarR = r; cachedStarG = g; cachedStarB = b;
    }

    // ==================== Sun ====================

    private static void renderSun(PoseStack poseStack, Matrix4f projectionMatrix,
                                   AtmospherePreset.SunConfig cfg, float blend,
                                   float celestialAngle) {
        if (!cfg.visible) return;

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(cfg.r, cfg.g, cfg.b, blend);

        ResourceLocation sunTex = resolveTexture(cfg.texture, SUN_LOCATION);
        RenderSystem.setShaderTexture(0, sunTex);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
        poseStack.mulPose(Axis.XP.rotationDegrees(celestialAngle * 360.0f));

        Matrix4f matrix = poseStack.last().pose();
        float size = 30.0f * cfg.scale;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, -size, 100, -size).uv(0, 0).endVertex();
        builder.vertex(matrix,  size, 100, -size).uv(1, 0).endVertex();
        builder.vertex(matrix,  size, 100,  size).uv(1, 1).endVertex();
        builder.vertex(matrix, -size, 100,  size).uv(0, 1).endVertex();
        tesselator.end();

        poseStack.popPose();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    // ==================== Moon ====================

    private static void renderMoon(PoseStack poseStack, Matrix4f projectionMatrix,
                                    AtmospherePreset.MoonConfig cfg, float blend,
                                    float celestialAngle, ClientLevel level) {
        if (!cfg.visible) return;

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(cfg.r, cfg.g, cfg.b, blend);

        ResourceLocation moonTex = resolveTexture(cfg.texture, MOON_LOCATION);
        RenderSystem.setShaderTexture(0, moonTex);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
        poseStack.mulPose(Axis.XP.rotationDegrees(celestialAngle * 360.0f + 180.0f)); // Opposite side from sun

        Matrix4f matrix = poseStack.last().pose();
        float size = 20.0f * cfg.scale;

        // Moon phase UV (4x2 grid on the moon texture)
        int moonPhase = level.getMoonPhase();
        int phaseX = moonPhase % 4;
        int phaseY = moonPhase / 4;
        float u0, u1, v0, v1;
        if (cfg.texture != null) {
            // Custom moon texture — use full UV
            u0 = 0; u1 = 1; v0 = 0; v1 = 1;
        } else {
            // Vanilla moon phases (4x2 grid)
            u0 = phaseX / 4f;
            u1 = (phaseX + 1) / 4f;
            v0 = phaseY / 2f;
            v1 = (phaseY + 1) / 2f;
        }

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, -size, -100, -size).uv(u1, v1).endVertex();
        builder.vertex(matrix,  size, -100, -size).uv(u0, v1).endVertex();
        builder.vertex(matrix,  size, -100,  size).uv(u0, v0).endVertex();
        builder.vertex(matrix, -size, -100,  size).uv(u1, v0).endVertex();
        tesselator.end();

        poseStack.popPose();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /**
     * Cleanup GPU resources. Called on world unload or engine stop.
     */
    public static void cleanup() {
        if (starBuffer != null) {
            starBuffer.close();
            starBuffer = null;
            cachedStarDensity = -1f;
            cachedStarR = -1f;
            cachedStarG = -1f;
            cachedStarB = -1f;
        }
        if (colorSkyBuffer != null) {
            colorSkyBuffer.close();
            colorSkyBuffer = null;
            cachedColorSkyKey = null;
        }
    }

    private static void ensureColorSkyBuffer(AtmospherePreset.SkyConfig cfg) {
        ColorSkyCacheKey key = ColorSkyCacheKey.from(cfg);
        if (colorSkyBuffer != null && key.equals(cachedColorSkyKey)) {
            return;
        }

        if (colorSkyBuffer != null) {
            colorSkyBuffer.close();
        }

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        appendUpperHemisphere(builder, cfg.zenithR, cfg.zenithG, cfg.zenithB,
                cfg.horizonR, cfg.horizonG, cfg.horizonB);
        appendLowerHemisphere(builder, cfg.horizonR, cfg.horizonG, cfg.horizonB);

        BufferBuilder.RenderedBuffer renderedBuffer = builder.end();
        colorSkyBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        colorSkyBuffer.bind();
        colorSkyBuffer.upload(renderedBuffer);
        VertexBuffer.unbind();
        cachedColorSkyKey = key;
    }

    private static void appendUpperHemisphere(BufferBuilder builder,
                                              float zenithR, float zenithG, float zenithB,
                                              float horizonR, float horizonG, float horizonB) {
        for (int i = 0; i < SKY_SEGMENTS; i++) {
            float angle0 = (float) (i * 2.0 * Math.PI / SKY_SEGMENTS);
            float angle1 = (float) ((i + 1) * 2.0 * Math.PI / SKY_SEGMENTS);

            float x0 = (float) (Math.cos(angle0) * SKY_RADIUS);
            float z0 = (float) (Math.sin(angle0) * SKY_RADIUS);
            float x1 = (float) (Math.cos(angle1) * SKY_RADIUS);
            float z1 = (float) (Math.sin(angle1) * SKY_RADIUS);

            builder.vertex(0, SKY_RADIUS, 0).color(zenithR, zenithG, zenithB, 1.0f).endVertex();
            builder.vertex(x0, 0, z0).color(horizonR, horizonG, horizonB, 1.0f).endVertex();
            builder.vertex(x1, 0, z1).color(horizonR, horizonG, horizonB, 1.0f).endVertex();
        }
    }

    private static void appendLowerHemisphere(BufferBuilder builder,
                                              float horizonR, float horizonG, float horizonB) {
        float nadirR = horizonR * 0.3f;
        float nadirG = horizonG * 0.3f;
        float nadirB = horizonB * 0.3f;

        for (int i = 0; i < SKY_SEGMENTS; i++) {
            float angle0 = (float) (i * 2.0 * Math.PI / SKY_SEGMENTS);
            float angle1 = (float) ((i + 1) * 2.0 * Math.PI / SKY_SEGMENTS);

            float x0 = (float) (Math.cos(angle0) * SKY_RADIUS);
            float z0 = (float) (Math.sin(angle0) * SKY_RADIUS);
            float x1 = (float) (Math.cos(angle1) * SKY_RADIUS);
            float z1 = (float) (Math.sin(angle1) * SKY_RADIUS);

            builder.vertex(0, -SKY_RADIUS, 0).color(nadirR, nadirG, nadirB, 1.0f).endVertex();
            builder.vertex(x1, 0, z1).color(horizonR, horizonG, horizonB, 1.0f).endVertex();
            builder.vertex(x0, 0, z0).color(horizonR, horizonG, horizonB, 1.0f).endVertex();
        }
    }

    @Nullable
    private static ResourceLocation resolveTexture(@Nullable String texturePath, @Nullable ResourceLocation fallback) {
        if (texturePath == null || texturePath.isBlank()) {
            return fallback;
        }

        ResourceLocation parsed = RenderResourceCache.get(texturePath);
        if (parsed != null) {
            return parsed;
        }

        if (texturePath.indexOf(':') < 0) {
            return RenderResourceCache.getOrCreate("minecraft", texturePath);
        }

        return fallback;
    }

    private record ColorSkyCacheKey(int zenithRBits, int zenithGBits, int zenithBBits,
                                    int horizonRBits, int horizonGBits, int horizonBBits) {
        private static ColorSkyCacheKey from(AtmospherePreset.SkyConfig cfg) {
            return new ColorSkyCacheKey(
                    Float.floatToIntBits(cfg.zenithR),
                    Float.floatToIntBits(cfg.zenithG),
                    Float.floatToIntBits(cfg.zenithB),
                    Float.floatToIntBits(cfg.horizonR),
                    Float.floatToIntBits(cfg.horizonG),
                    Float.floatToIntBits(cfg.horizonB)
            );
        }
    }
}
