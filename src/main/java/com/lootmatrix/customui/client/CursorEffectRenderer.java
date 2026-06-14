package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.config.CursorEffectConfig;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

@Mod.EventBusSubscriber(
        modid = Main.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public class CursorEffectRenderer {

    private static final ResourceLocation TRAIL_GLOW_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/cursor/trail_glow.png");
    private static final ResourceLocation SOFT_PARTICLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/cursor/soft_particle.png");
    private static final ResourceLocation CURSOR_DOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/cursor/cursor_dot.png");

    private static final int DEFAULT_MAX_TRAIL_POINTS = 150;
    private static final long DEFAULT_TRAIL_LIFETIME_MS = 250;
    private static final float DEFAULT_ALPHA_HEAD = 0.85f;
    private static final float DEFAULT_WIDTH_HEAD = 4.0f;
    private static final float DEFAULT_WIDTH_TAIL = 0.5f;
    private static final float DEFAULT_RIPPLE_TIME = 520f;
    private static final float DEFAULT_RIPPLE_RADIUS = 44f;
    private static final float DEFAULT_RIPPLE_ALPHA = 0.8f;

    private static final long MIN_SAMPLE_INTERVAL_MS = 8;
    private static final double MIN_SAMPLE_DISTANCE = 1.5;
    private static final double MAX_SAMPLE_DISTANCE = 6.0;
    private static final float MOTION_RISE_MS = 70f;
    private static final float MOTION_FALL_MS = 180f;

    private static final int CYAN_OUTER_R = 67;
    private static final int CYAN_OUTER_G = 219;
    private static final int CYAN_OUTER_B = 255;
    private static final int CYAN_MID_R = 105;
    private static final int CYAN_MID_G = 235;
    private static final int CYAN_MID_B = 255;
    private static final int WHITE_R = 245;
    private static final int WHITE_G = 252;
    private static final int WHITE_B = 255;

    private static final Deque<TrailPoint> trail = new ArrayDeque<>();
    private static final Deque<Ripple> ripples = new ArrayDeque<>();
    private static final Deque<RippleParticle> particles = new ArrayDeque<>();
    private static final ArrayList<TrailPoint> renderBuffer = new ArrayList<>(DEFAULT_MAX_TRAIL_POINTS);
    private static final ArrayList<TrailPoint> smoothBuffer = new ArrayList<>(DEFAULT_MAX_TRAIL_POINTS);

    private static double lastSampleX = Double.NaN;
    private static double lastSampleY = Double.NaN;
    private static long lastSampleTime = 0;
    private static float lastDirX = 1f;
    private static float lastDirY = 0f;
    private static boolean wasActiveLastFrame = true;
    private static boolean systemCursorHidden = false;
    private static long lastClickTime = 0;
    private static long lastFrameTime = 0;
    private static double lastFrameMouseX = Double.NaN;
    private static double lastFrameMouseY = Double.NaN;
    private static float motionEnergy = 0f;
    private static float renderQuality = 1f;

    private CursorEffectRenderer() {
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (shouldPauseAndMaybeReset()) {
            ensureSystemCursor(false);
            return;
        }
        if (BackgroundGuard.shouldSkip()) {
            ensureSystemCursor(false);
            return;
        }

        boolean trailEnabled = isTrailEnabled();
        boolean rippleEnabled = isRippleEnabled();
        boolean cursorEnabled = isCustomCursorEnabled();
        boolean drawAny = trailEnabled || rippleEnabled || cursorEnabled;
        if (!drawAny) {
            ensureSystemCursor(false);
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        updateFrameDynamics(mouseX, mouseY);
        if (trailEnabled) {
            updateTrail(mouseX, mouseY);
        }

        renderTopLayer(event.getGuiGraphics(), trailEnabled, rippleEnabled, cursorEnabled, mouseX, mouseY);
    }

    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (shouldPauseAndMaybeReset()) {
            ensureSystemCursor(false);
            return;
        }
        if (BackgroundGuard.shouldSkip() || !isRippleEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        int seed = (int) (now ^ Double.doubleToLongBits(event.getMouseX()) ^ Double.doubleToLongBits(event.getMouseY()));
        int maxRipples = getRippleMaxCount();
        while (ripples.size() >= maxRipples) {
            ripples.pollFirst();
        }
        Ripple ripple = new Ripple((float) event.getMouseX(), (float) event.getMouseY(), now, seed);
        ripples.addLast(ripple);
        spawnRippleParticles(ripple);
        lastClickTime = now;
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        ensureSystemCursor(false);
        resetState();
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() == null) {
            ensureSystemCursor(false);
            resetState();
        }
    }

    public static void restoreSystemCursor() {
        ensureSystemCursor(false);
    }

    private static void renderTopLayer(GuiGraphics graphics, boolean trailEnabled, boolean rippleEnabled,
                                       boolean cursorEnabled, double mouseX, double mouseY) {
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 1000.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        try {
            if (trailEnabled) {
                renderTrail(graphics);
            }
            if (rippleEnabled) {
                renderRipples(graphics);
                renderParticles(graphics);
            } else {
                particles.clear();
                ripples.clear();
            }
            if (cursorEnabled) {
                ensureSystemCursor(shouldHideSystemCursor());
                renderCustomCursor(graphics, (float) mouseX, (float) mouseY);
            } else {
                ensureSystemCursor(false);
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            graphics.pose().popPose();
        }
    }

    private static void updateTrail(double x, double y) {
        long now = System.currentTimeMillis();
        long trailLifetime = getTrailLifetimeMs();
        int maxPoints = getMaxTrailPoints();

        if (!Double.isFinite(x) || !Double.isFinite(y) || x < -10_000 || y < -10_000 || x > 100_000 || y > 100_000) {
            resetState();
            return;
        }

        if (!trail.isEmpty() && now - lastSampleTime > trailLifetime * 2) {
            resetState();
        }

        while (!trail.isEmpty() && now - trail.peekFirst().time > trailLifetime) {
            trail.pollFirst();
        }

        if (Double.isNaN(lastSampleX)) {
            lastSampleX = x;
            lastSampleY = y;
            lastSampleTime = now;
            trail.addLast(new TrailPoint((float) x, (float) y, now, lastDirX, lastDirY));
            return;
        }

        double dx = x - lastSampleX;
        double dy = y - lastSampleY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        long timeSinceLastSample = now - lastSampleTime;
        if (dist < MIN_SAMPLE_DISTANCE || timeSinceLastSample < MIN_SAMPLE_INTERVAL_MS) {
            return;
        }

        float dirX;
        float dirY;
        if (dist > 0.001) {
            dirX = (float) (dx / dist);
            dirY = (float) (dy / dist);
            lastDirX = dirX;
            lastDirY = dirY;
        } else {
            dirX = lastDirX;
            dirY = lastDirY;
        }

        double adaptiveDistance = MAX_SAMPLE_DISTANCE / Math.max(0.75f, renderQuality);
        int steps = Math.max(1, (int) Math.ceil(dist / adaptiveDistance));
        steps = Math.min(steps, renderQuality > 1.25f ? 48 : 32);
        while (trail.size() + steps >= maxPoints) {
            trail.pollFirst();
        }

        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            float px = (float) Mth.lerp(t, lastSampleX, x);
            float py = (float) Mth.lerp(t, lastSampleY, y);
            trail.addLast(new TrailPoint(px, py, now, dirX, dirY));
        }

        lastSampleX = x;
        lastSampleY = y;
        lastSampleTime = now;
    }

    private static void renderTrail(GuiGraphics graphics) {
        if (trail.size() < 2) {
            return;
        }

        long now = System.currentTimeMillis();
        long trailLifetime = getTrailLifetimeMs();
        renderBuffer.clear();
        for (TrailPoint point : trail) {
            if (now - point.time < trailLifetime) {
                renderBuffer.add(point);
            }
        }
        if (renderBuffer.size() < 2) {
            return;
        }

        List<TrailPoint> points = smoothedTrail();
        Matrix4f matrix = graphics.pose().last().pose();

        beginAdditiveTexture(TRAIL_GLOW_TEXTURE);
        drawTrailLayer(matrix, points, now, trailLifetime, getTrailOuterGlowWidthMultiplier(),
                CYAN_OUTER_R, CYAN_OUTER_G, CYAN_OUTER_B, getAlphaHead() * 0.42f, true);
        drawTrailLayer(matrix, points, now, trailLifetime, getTrailMiddleWidthMultiplier(),
                CYAN_MID_R, CYAN_MID_G, CYAN_MID_B, getAlphaHead() * 0.68f, false);
        drawTrailLayer(matrix, points, now, trailLifetime, getTrailCoreWidthMultiplier(),
                WHITE_R, WHITE_G, WHITE_B, getAlphaHead() * 0.92f, false);
        endAdditiveTexture();

        TrailPoint head = points.get(points.size() - 1);
        beginAdditiveTexture(SOFT_PARTICLE_TEXTURE);
        float headPulse = 0.65f + 0.35f * motionEnergy;
        drawTexturedQuad(matrix, head.x, head.y, getWidthHead() * 5.0f * headPulse, getWidthHead() * 5.0f * headPulse,
                CYAN_MID_R, CYAN_MID_G, CYAN_MID_B, alphaToInt(0.36f * headPulse));
        drawTexturedQuad(matrix, head.x, head.y, getWidthHead() * 1.4f, getWidthHead() * 1.4f,
                WHITE_R, WHITE_G, WHITE_B, alphaToInt(0.74f * headPulse));
        endAdditiveTexture();
    }

    private static List<TrailPoint> smoothedTrail() {
        float smoothing = getTrailSmoothing();
        if (smoothing <= 0.001f || renderBuffer.size() < 3) {
            return renderBuffer;
        }

        smoothBuffer.clear();
        smoothBuffer.add(renderBuffer.get(0));
        for (int i = 1; i < renderBuffer.size() - 1; i++) {
            TrailPoint previous = renderBuffer.get(i - 1);
            TrailPoint current = renderBuffer.get(i);
            TrailPoint next = renderBuffer.get(i + 1);
            float x = current.x * (1f - smoothing) + ((previous.x + current.x + next.x) / 3f) * smoothing;
            float y = current.y * (1f - smoothing) + ((previous.y + current.y + next.y) / 3f) * smoothing;
            smoothBuffer.add(new TrailPoint(x, y, current.time, current.dirX, current.dirY));
        }
        smoothBuffer.add(renderBuffer.get(renderBuffer.size() - 1));
        return smoothBuffer;
    }

    private static void drawTrailLayer(Matrix4f matrix, List<TrailPoint> points, long now, long trailLifetime,
                                       float widthMultiplier, int r, int g, int b, float alphaMultiplier,
                                       boolean featherEdges) {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_TEX_COLOR);

        float previousPerpX = 0f;
        float previousPerpY = 0f;
        boolean hasPreviousPerp = false;
        int count = points.size();

        for (int i = 0; i < count; i++) {
            TrailPoint point = points.get(i);
            float positionFactor = count == 1 ? 1f : (float) i / (count - 1);
            float age = (float) (now - point.time) / trailLifetime;
            float timeFade = Math.max(0f, 1f - age);
            float transition = 0.42f + motionEnergy * 0.58f;
            float alpha = alphaMultiplier * (float) Math.pow(timeFade, 1.55f)
                    * CursorEffectMath.smoothstep(positionFactor) * transition;
            int a = alphaToInt(alpha);

            float[] dir = directionAt(points, i);
            float perpX = -dir[1];
            float perpY = dir[0];
            if (hasPreviousPerp && (perpX * previousPerpX + perpY * previousPerpY) < 0f) {
                perpX = -perpX;
                perpY = -perpY;
            }
            previousPerpX = perpX;
            previousPerpY = perpY;
            hasPreviousPerp = true;

            float width = Mth.lerp(positionFactor, getWidthTail(), getWidthHead())
                    * widthMultiplier * (0.72f + motionEnergy * 0.28f);
            float halfWidth = width * 0.5f;
            if (featherEdges) {
                halfWidth += 0.75f;
            }
            float x1 = point.x + perpX * halfWidth;
            float y1 = point.y + perpY * halfWidth;
            float x2 = point.x - perpX * halfWidth;
            float y2 = point.y - perpY * halfWidth;
            float u = positionFactor;

            builder.vertex(matrix, x1, y1, 0).uv(u, 0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, x2, y2, 0).uv(u, 1f).color(r, g, b, a).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
    }

    private static float[] directionAt(List<TrailPoint> points, int index) {
        TrailPoint from = points.get(Math.max(0, index - 1));
        TrailPoint to = points.get(Math.min(points.size() - 1, index + 1));
        float dx = to.x - from.x;
        float dy = to.y - from.y;
        float length = Mth.sqrt(dx * dx + dy * dy);
        if (length < 0.001f) {
            TrailPoint point = points.get(index);
            return new float[]{point.dirX, point.dirY};
        }
        return new float[]{dx / length, dy / length};
    }

    private static void renderRipples(GuiGraphics graphics) {
        if (ripples.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Matrix4f matrix = graphics.pose().last().pose();
        beginAdditiveTexture(TRAIL_GLOW_TEXTURE);
        Iterator<Ripple> iterator = ripples.iterator();
        while (iterator.hasNext()) {
            Ripple ripple = iterator.next();
            float progress = ripple.progress(now);
            if (progress >= 1f) {
                iterator.remove();
                continue;
            }
            drawWavyRingLayer(matrix, ripple, progress, 1.00f, 7.0f, getRippleAlpha() * 0.44f,
                    CYAN_OUTER_R, CYAN_OUTER_G, CYAN_OUTER_B);
            drawWavyRingLayer(matrix, ripple, progress, 0.96f, 2.25f, getRippleAlpha() * 0.86f,
                    WHITE_R, WHITE_G, WHITE_B);
        }
        endAdditiveTexture();
    }

    private static void drawWavyRingLayer(Matrix4f matrix, Ripple ripple, float progress, float radiusScale,
                                          float width, float alphaMultiplier, int r, int g, int b) {
        float eased = CursorEffectMath.easeOutCubic(progress);
        float baseRadius = (10f + eased * getRippleRadius()) * radiusScale;
        float innerOffset = width * 0.5f;
        int alpha = alphaToInt((float) Math.pow(1f - progress, 1.7f) * alphaMultiplier);
        if (alpha <= 0) {
            return;
        }

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_TEX_COLOR);
        int segments = CursorEffectMath.adaptiveSegments(128, renderQuality, 96, 192);
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (Math.PI * 2.0 * i / segments);
            float radius = CursorEffectMath.waveRadius(
                    baseRadius, angle, progress, getRippleWaveAmplitude(), getRippleWaveFrequency(), ripple.seed);
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            float outer = radius + innerOffset;
            float inner = Math.max(0f, radius - innerOffset);
            float u = (float) i / segments;

            builder.vertex(matrix, ripple.x + cos * outer, ripple.y + sin * outer, 0)
                    .uv(u, 0f).color(r, g, b, alpha).endVertex();
            builder.vertex(matrix, ripple.x + cos * inner, ripple.y + sin * inner, 0)
                    .uv(u, 1f).color(r, g, b, alpha).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private static void spawnRippleParticles(Ripple ripple) {
        int particleMax = getRippleParticleMaxCount();
        if (particleMax <= 0) {
            particles.clear();
            return;
        }
        List<CursorEffectMath.ParticleSeed> seeds = CursorEffectMath.createRippleParticles(
                ripple.x, ripple.y, getRippleParticleCount(), particleMax, getRippleParticleSpeed(),
                getRippleParticleLifetimeMs(), ripple.start, ripple.seed);
        for (CursorEffectMath.ParticleSeed seed : seeds) {
            particles.addLast(new RippleParticle(seed));
        }
        while (particles.size() > particleMax) {
            particles.pollFirst();
        }
    }

    private static void renderParticles(GuiGraphics graphics) {
        if (particles.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Matrix4f matrix = graphics.pose().last().pose();
        beginAdditiveTexture(SOFT_PARTICLE_TEXTURE);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        Iterator<RippleParticle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            RippleParticle particle = iterator.next();
            float age = particle.age(now);
            if (age >= 1f) {
                iterator.remove();
                continue;
            }
            float eased = CursorEffectMath.easeOutCubic(age);
            float normalX = Mth.cos(particle.angle);
            float normalY = Mth.sin(particle.angle);
            float tangentX = -normalY;
            float tangentY = normalX;
            float wobble = Mth.sin(age * (float) (Math.PI * 2.0) + particle.speed) * particle.tangent * (1f - age);
            float x = particle.x + normalX * particle.speed * eased + tangentX * wobble;
            float y = particle.y + normalY * particle.speed * eased + tangentY * wobble;
            float size = Math.max(0.4f, particle.size * (1f - age * 0.58f)) * 3.2f;
            int alpha = alphaToInt((float) Math.pow(1f - age, 2.2f) * 0.82f);
            addTexturedQuad(builder, matrix, x, y, size, size, CYAN_MID_R, CYAN_MID_G, CYAN_MID_B, alpha);
        }

        BufferUploader.drawWithShader(builder.end());
        endAdditiveTexture();
    }

    private static void renderCustomCursor(GuiGraphics graphics, float mouseX, float mouseY) {
        long now = System.currentTimeMillis();
        float dotRadius = getCursorDotRadius();
        float glowRadius = getCursorGlowRadius();
        float pulse = Mth.sin((now % 2000L) / 2000f * (float) (Math.PI * 2.0)) * getCursorPulseAmplitude();
        float clickBoost = 0f;
        long clickAge = now - lastClickTime;
        if (clickAge >= 0 && clickAge < 180L) {
            clickBoost = 1f - clickAge / 180f;
        }
        int alpha = alphaToInt(getCursorAlpha());
        Matrix4f matrix = graphics.pose().last().pose();

        beginAdditiveTexture(SOFT_PARTICLE_TEXTURE);
        float outerSize = Math.max(2f, (glowRadius + pulse + clickBoost * 4f) * 2f);
        drawTexturedQuad(matrix, mouseX, mouseY, outerSize, outerSize,
                CYAN_MID_R, CYAN_MID_G, CYAN_MID_B, alphaToInt(getCursorAlpha() * 0.62f));
        endAdditiveTexture();

        beginAdditiveTexture(CURSOR_DOT_TEXTURE);
        float dotSize = Math.max(1f, (dotRadius + clickBoost * 1.2f) * 2f);
        drawTexturedQuad(matrix, mouseX, mouseY, dotSize, dotSize, WHITE_R, WHITE_G, WHITE_B, alpha);
        endAdditiveTexture();
    }

    private static void drawTexturedQuad(Matrix4f matrix, float centerX, float centerY, float width, float height,
                                         int r, int g, int b, int a) {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        addTexturedQuad(builder, matrix, centerX, centerY, width, height, r, g, b, a);
        BufferUploader.drawWithShader(builder.end());
    }

    private static void addTexturedQuad(BufferBuilder builder, Matrix4f matrix, float centerX, float centerY,
                                        float width, float height, int r, int g, int b, int a) {
        float halfW = width * 0.5f;
        float halfH = height * 0.5f;
        float left = centerX - halfW;
        float right = centerX + halfW;
        float top = centerY - halfH;
        float bottom = centerY + halfH;
        builder.vertex(matrix, left, bottom, 0).uv(0f, 1f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, right, bottom, 0).uv(1f, 1f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, right, top, 0).uv(1f, 0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, left, top, 0).uv(0f, 0f).color(r, g, b, a).endVertex();
    }

    private static void beginAdditiveTexture(ResourceLocation texture) {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
    }

    private static void endAdditiveTexture() {
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static boolean shouldPauseAndMaybeReset() {
        Minecraft mc = Minecraft.getInstance();
        boolean active = mc.isWindowActive();
        if (wasActiveLastFrame && !active) {
            resetState();
        }
        wasActiveLastFrame = active;

        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        return !active || width <= 0 || height <= 0;
    }

    private static void resetState() {
        trail.clear();
        ripples.clear();
        particles.clear();
        lastSampleX = Double.NaN;
        lastSampleY = Double.NaN;
        lastSampleTime = 0;
        lastFrameTime = 0;
        lastFrameMouseX = Double.NaN;
        lastFrameMouseY = Double.NaN;
        motionEnergy = 0f;
        renderQuality = 1f;
    }

    private static void updateFrameDynamics(double mouseX, double mouseY) {
        long now = System.currentTimeMillis();
        if (lastFrameTime <= 0L || Double.isNaN(lastFrameMouseX)) {
            lastFrameTime = now;
            lastFrameMouseX = mouseX;
            lastFrameMouseY = mouseY;
            motionEnergy = 0f;
            return;
        }

        float elapsedMs = Math.max(1f, Math.min(100f, now - lastFrameTime));
        double dx = mouseX - lastFrameMouseX;
        double dy = mouseY - lastFrameMouseY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        float speedPxPerMs = (float) (distance / elapsedMs);
        float targetEnergy = CursorEffectMath.clamp01((speedPxPerMs - 0.02f) / 0.55f);
        motionEnergy = CursorEffectMath.transitionToward(
                motionEnergy, targetEnergy, elapsedMs, MOTION_RISE_MS, MOTION_FALL_MS);
        renderQuality = CursorEffectMath.adaptiveQuality(elapsedMs);
        lastFrameTime = now;
        lastFrameMouseX = mouseX;
        lastFrameMouseY = mouseY;
    }

    private static void ensureSystemCursor(boolean hidden) {
        if (systemCursorHidden == hidden) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR,
                hidden ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
        systemCursorHidden = hidden;
    }

    private static int alphaToInt(float alpha) {
        return AlphaFadeHelper.clampAlphaInt((int) (CursorEffectMath.clamp01(alpha) * 255f));
    }

    private static int getMaxTrailPoints() {
        try {
            return CursorEffectConfig.INSTANCE.trailMaxPoints.get();
        } catch (Exception e) {
            return DEFAULT_MAX_TRAIL_POINTS;
        }
    }

    private static long getTrailLifetimeMs() {
        try {
            return CursorEffectConfig.INSTANCE.trailLifetimeMs.get();
        } catch (Exception e) {
            return DEFAULT_TRAIL_LIFETIME_MS;
        }
    }

    private static float getAlphaHead() {
        try {
            return CursorEffectConfig.INSTANCE.trailAlphaHead.get().floatValue();
        } catch (Exception e) {
            return DEFAULT_ALPHA_HEAD;
        }
    }

    private static float getWidthHead() {
        try {
            return CursorEffectConfig.INSTANCE.trailWidthHead.get().floatValue();
        } catch (Exception e) {
            return DEFAULT_WIDTH_HEAD;
        }
    }

    private static float getWidthTail() {
        try {
            return CursorEffectConfig.INSTANCE.trailWidthTail.get().floatValue();
        } catch (Exception e) {
            return DEFAULT_WIDTH_TAIL;
        }
    }

    private static float getTrailSmoothing() {
        try {
            return CursorEffectConfig.INSTANCE.trailSmoothing.get().floatValue();
        } catch (Exception e) {
            return 0.42f;
        }
    }

    private static float getTrailOuterGlowWidthMultiplier() {
        try {
            return CursorEffectConfig.INSTANCE.trailOuterGlowWidthMultiplier.get().floatValue();
        } catch (Exception e) {
            return 2.9f;
        }
    }

    private static float getTrailMiddleWidthMultiplier() {
        try {
            return CursorEffectConfig.INSTANCE.trailMiddleWidthMultiplier.get().floatValue();
        } catch (Exception e) {
            return 1.45f;
        }
    }

    private static float getTrailCoreWidthMultiplier() {
        try {
            return CursorEffectConfig.INSTANCE.trailCoreWidthMultiplier.get().floatValue();
        } catch (Exception e) {
            return 0.42f;
        }
    }

    private static boolean isTrailEnabled() {
        try {
            return CursorEffectConfig.INSTANCE.enabled.get() && CursorEffectConfig.INSTANCE.trailEnabled.get();
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isRippleEnabled() {
        try {
            return CursorEffectConfig.INSTANCE.enabled.get() && CursorEffectConfig.INSTANCE.rippleEnabled.get();
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isCustomCursorEnabled() {
        try {
            if (!CursorEffectConfig.INSTANCE.enabled.get() || !CursorEffectConfig.INSTANCE.cursorEnabled.get()) {
                return false;
            }
            String scope = CursorEffectConfig.INSTANCE.cursorScope.get();
            Minecraft mc = Minecraft.getInstance();
            if ("VISIBLE_CURSOR".equalsIgnoreCase(scope)) {
                return mc.screen != null || !mc.mouseHandler.isMouseGrabbed();
            }
            return mc.screen != null;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean shouldHideSystemCursor() {
        try {
            return CursorEffectConfig.INSTANCE.cursorHideSystemCursor.get();
        } catch (Exception e) {
            return true;
        }
    }

    private static float getRippleTime() {
        try {
            return CursorEffectConfig.INSTANCE.rippleDurationMs.get();
        } catch (Exception e) {
            return DEFAULT_RIPPLE_TIME;
        }
    }

    private static float getRippleRadius() {
        try {
            return CursorEffectConfig.INSTANCE.rippleRadius.get().floatValue();
        } catch (Exception e) {
            return DEFAULT_RIPPLE_RADIUS;
        }
    }

    private static float getRippleAlpha() {
        try {
            return CursorEffectConfig.INSTANCE.rippleAlpha.get().floatValue();
        } catch (Exception e) {
            return DEFAULT_RIPPLE_ALPHA;
        }
    }

    private static int getRippleMaxCount() {
        try {
            return CursorEffectConfig.INSTANCE.rippleMaxCount.get();
        } catch (Exception e) {
            return 10;
        }
    }

    private static float getRippleWaveAmplitude() {
        try {
            return CursorEffectConfig.INSTANCE.rippleWaveAmplitude.get().floatValue();
        } catch (Exception e) {
            return 4f;
        }
    }

    private static int getRippleWaveFrequency() {
        try {
            return CursorEffectConfig.INSTANCE.rippleWaveFrequency.get();
        } catch (Exception e) {
            return 6;
        }
    }

    private static int getRippleParticleCount() {
        try {
            return CursorEffectConfig.INSTANCE.rippleParticleCount.get();
        } catch (Exception e) {
            return 30;
        }
    }

    private static float getRippleParticleSpeed() {
        try {
            return CursorEffectConfig.INSTANCE.rippleParticleSpeed.get().floatValue();
        } catch (Exception e) {
            return 46f;
        }
    }

    private static long getRippleParticleLifetimeMs() {
        try {
            return CursorEffectConfig.INSTANCE.rippleParticleLifetimeMs.get();
        } catch (Exception e) {
            return 520L;
        }
    }

    private static int getRippleParticleMaxCount() {
        try {
            return CursorEffectConfig.INSTANCE.rippleParticleMaxCount.get();
        } catch (Exception e) {
            return 180;
        }
    }

    private static float getCursorDotRadius() {
        try {
            return CursorEffectConfig.INSTANCE.cursorDotRadius.get().floatValue();
        } catch (Exception e) {
            return 2f;
        }
    }

    private static float getCursorGlowRadius() {
        try {
            return CursorEffectConfig.INSTANCE.cursorGlowRadius.get().floatValue();
        } catch (Exception e) {
            return 8f;
        }
    }

    private static float getCursorPulseAmplitude() {
        try {
            return CursorEffectConfig.INSTANCE.cursorPulseAmplitude.get().floatValue();
        } catch (Exception e) {
            return 0.8f;
        }
    }

    private static float getCursorAlpha() {
        try {
            return CursorEffectConfig.INSTANCE.cursorAlpha.get().floatValue();
        } catch (Exception e) {
            return 0.9f;
        }
    }

    private record TrailPoint(float x, float y, long time, float dirX, float dirY) {
    }

    private static final class Ripple {
        final float x;
        final float y;
        final long start;
        final int seed;

        Ripple(float x, float y, long start, int seed) {
            this.x = x;
            this.y = y;
            this.start = start;
            this.seed = seed;
        }

        float progress(long now) {
            return Math.min(1f, (now - start) / getRippleTime());
        }
    }

    private static final class RippleParticle {
        final float x;
        final float y;
        final float angle;
        final float speed;
        final float tangent;
        final float size;
        final long lifetimeMs;
        final long startTimeMs;

        RippleParticle(CursorEffectMath.ParticleSeed seed) {
            this.x = seed.x();
            this.y = seed.y();
            this.angle = seed.angle();
            this.speed = seed.speed();
            this.tangent = seed.tangent();
            this.size = seed.size();
            this.lifetimeMs = seed.lifetimeMs();
            this.startTimeMs = seed.startTimeMs();
        }

        float age(long now) {
            if (lifetimeMs <= 0L) {
                return 1f;
            }
            return CursorEffectMath.clamp01((float) (now - startTimeMs) / lifetimeMs);
        }
    }
}
