package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.CaptureZoneGeometrySyncPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Renders 3D wireframe boundaries. for active capture zones in world space.
 * Uses the zone geometry synced from the server via {@link CaptureZoneGeometrySyncPacket}.
 *
 * Rendering style:
 * - Pulsating translucent wireframe outlines for each CSG shape
 * - Color reflects zone status (neutral/capturing/contested/captured)
 * - Distance-based fade: fully visible within 64 blocks, fades from 64-128
 * - Only renders "add" shapes (subtract carve-outs are not drawn for clarity)
 */
@OnlyIn(Dist.CLIENT)
public class CaptureZoneBoundaryRenderer {

    private static final CaptureZoneBoundaryRenderer INSTANCE = new CaptureZoneBoundaryRenderer();
    public static CaptureZoneBoundaryRenderer getInstance() { return INSTANCE; }

    /** Number of segments for cylinder/sphere outlines. */
    private static final int WIRE_SEGMENTS = 48;
    /** Max render distance in blocks. */
    private static final double MAX_RENDER_DIST = 128.0;
    /** Distance at which fading begins. */
    private static final double FADE_START_DIST = 64.0;

    /** Client-side zone geometry cache. */
    private final Map<String, ZoneGeometry> zoneGeometries = new LinkedHashMap<>();

    public static class ZoneGeometry {
        public String zoneId;
        public String displayName;
        public double originX, originY, originZ;
        public List<ShapeRenderData> shapes = new ArrayList<>();
    }

    public static class ShapeRenderData {
        public String type; // cylinder, box, sphere
        public String mode; // add, subtract
        public double centerX, centerY, centerZ;
        public double radius, height;
        public double minX, minY, minZ;
        public double maxX, maxY, maxZ;

        /** Get the approximate world-space center of this shape. Cached to avoid per-frame allocation. */
        private Vec3 cachedCenter = null;
        public Vec3 getCenter() {
            if (cachedCenter == null) {
                cachedCenter = switch (type) {
                    case "box" -> new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
                    case "cylinder" -> new Vec3(centerX, centerY + height / 2, centerZ);
                    default -> new Vec3(centerX, centerY, centerZ);
                };
            }
            return cachedCenter;
        }
    }

    public void updateGeometry(String zoneId, String displayName,
                                double originX, double originY, double originZ,
                                List<CaptureZoneGeometrySyncPacket.ShapeData> shapes) {
        ZoneGeometry geo = new ZoneGeometry();
        geo.zoneId = zoneId;
        geo.displayName = displayName;
        geo.originX = originX;
        geo.originY = originY;
        geo.originZ = originZ;
        for (CaptureZoneGeometrySyncPacket.ShapeData s : shapes) {
            ShapeRenderData rd = new ShapeRenderData();
            rd.type = s.type; rd.mode = s.mode;
            rd.centerX = s.centerX; rd.centerY = s.centerY; rd.centerZ = s.centerZ;
            rd.radius = s.radius; rd.height = s.height;
            rd.minX = s.minX; rd.minY = s.minY; rd.minZ = s.minZ;
            rd.maxX = s.maxX; rd.maxY = s.maxY; rd.maxZ = s.maxZ;
            geo.shapes.add(rd);
        }
        zoneGeometries.put(zoneId, geo);
    }

    public void clearAll() {
        zoneGeometries.clear();
    }

    @Nullable
    public ZoneGeometry getGeometry(String zoneId) {
        return zoneGeometries.get(zoneId);
    }

    /** Get the world-space center of the first "add" shape for a zone (used for projection). */
    @Nullable
    public Vec3 getZoneWorldCenter(String zoneId) {
        ZoneGeometry geo = zoneGeometries.get(zoneId);
        if (geo == null || geo.shapes.isEmpty()) return null;
        for (ShapeRenderData s : geo.shapes) {
            if ("add".equals(s.mode)) return s.getCenter();
        }
        return geo.shapes.get(0).getCenter();
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (zoneGeometries.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partialTick = event.getPartialTick();
        long gameTime = mc.level.getGameTime();

        // Pulsating alpha: sin wave over 2 seconds (40 ticks)
        float pulse = 0.5f + 0.3f * (float) Math.sin((gameTime + partialTick) * Math.PI / 20.0);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.0f);

        CaptureZoneHudRenderer hud = CaptureZoneHudRenderer.getInstance();

        for (ZoneGeometry geo : zoneGeometries.values()) {
            // Get zone status colors from HUD state
            CaptureZoneHudRenderer.ZoneClientState hudState = hud.getZoneState(geo.zoneId);
            int color = getZoneBorderColor(hudState, pulse);

            for (ShapeRenderData shape : geo.shapes) {
                // Only render "add" shapes for clarity (subtract carve-outs are left invisible)
                if (!"add".equals(shape.mode)) continue;

                // Distance check using squared distance for fast cull (avoids sqrt)
                Vec3 shapeCenter = shape.getCenter();
                double distSqr = camPos.distanceToSqr(shapeCenter);
                if (distSqr > MAX_RENDER_DIST * MAX_RENDER_DIST) continue;
                double dist = Math.sqrt(distSqr);

                // Distance-based fade
                float distAlpha = dist < FADE_START_DIST ? 1.0f :
                        (float) (1.0 - (dist - FADE_START_DIST) / (MAX_RENDER_DIST - FADE_START_DIST));
                float finalAlpha = ((color >> 24) & 0xFF) / 255f * distAlpha;
                int finalColor = (Math.round(finalAlpha * 255) << 24) | (color & 0x00FFFFFF);

                poseStack.pushPose();
                poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

                switch (shape.type) {
                    case "cylinder" -> renderCylinderWireframe(poseStack, shape, finalColor);
                    case "box" -> renderBoxWireframe(poseStack, shape, finalColor);
                    case "sphere" -> renderSphereWireframe(poseStack, shape, finalColor);
                }

                poseStack.popPose();
            }
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
    }

    private int getZoneBorderColor(@Nullable CaptureZoneHudRenderer.ZoneClientState state, float pulse) {
        int alpha = Math.round(pulse * 180);
        if (state == null) return (alpha << 24) | 0x4488FF; // neutral blue
        if (state.contested) return (alpha << 24) | 0xFF8800; // orange
        if (state.ownerTeam != null) return (alpha << 24) | 0x44FF44; // green
        if (state.capturingTeam != null) return (alpha << 24) | 0x88CCFF; // light blue
        return (alpha << 24) | 0x4488FF; // neutral blue
    }

    // ==================== Cylinder Wireframe ====================

    private void renderCylinderWireframe(PoseStack poseStack, ShapeRenderData shape, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = ((color >> 24) & 0xFF) / 255f;

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        double cx = shape.centerX, cy = shape.centerY, cz = shape.centerZ;
        double rad = shape.radius, h = shape.height;

        // Top and bottom rings
        for (int i = 0; i < WIRE_SEGMENTS; i++) {
            double angle1 = 2.0 * Math.PI * i / WIRE_SEGMENTS;
            double angle2 = 2.0 * Math.PI * (i + 1) / WIRE_SEGMENTS;
            float x1 = (float) (cx + Math.cos(angle1) * rad);
            float z1 = (float) (cz + Math.sin(angle1) * rad);
            float x2 = (float) (cx + Math.cos(angle2) * rad);
            float z2 = (float) (cz + Math.sin(angle2) * rad);

            // Bottom ring
            buf.vertex(matrix, x1, (float) cy, z1).color(r, g, b, a).endVertex();
            buf.vertex(matrix, x2, (float) cy, z2).color(r, g, b, a).endVertex();

            // Top ring
            buf.vertex(matrix, x1, (float) (cy + h), z1).color(r, g, b, a).endVertex();
            buf.vertex(matrix, x2, (float) (cy + h), z2).color(r, g, b, a).endVertex();

            // Vertical lines (every 4th segment)
            if (i % 4 == 0) {
                buf.vertex(matrix, x1, (float) cy, z1).color(r, g, b, a).endVertex();
                buf.vertex(matrix, x1, (float) (cy + h), z1).color(r, g, b, a).endVertex();
            }
        }

        BufferUploader.drawWithShader(buf.end());
    }

    // ==================== Box Wireframe ====================

    private void renderBoxWireframe(PoseStack poseStack, ShapeRenderData shape, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = ((color >> 24) & 0xFF) / 255f;

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        float x0 = (float) shape.minX, y0 = (float) shape.minY, z0 = (float) shape.minZ;
        float x1 = (float) shape.maxX, y1 = (float) shape.maxY, z1 = (float) shape.maxZ;

        // Bottom face
        line(buf, matrix, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(buf, matrix, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(buf, matrix, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(buf, matrix, x0, y0, z1, x0, y0, z0, r, g, b, a);
        // Top face
        line(buf, matrix, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(buf, matrix, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(buf, matrix, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(buf, matrix, x0, y1, z1, x0, y1, z0, r, g, b, a);
        // Verticals
        line(buf, matrix, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(buf, matrix, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(buf, matrix, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(buf, matrix, x0, y0, z1, x0, y1, z1, r, g, b, a);

        BufferUploader.drawWithShader(buf.end());
    }

    // ==================== Sphere Wireframe ====================

    private void renderSphereWireframe(PoseStack poseStack, ShapeRenderData shape, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = ((color >> 24) & 0xFF) / 255f;

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        double cx = shape.centerX, cy = shape.centerY, cz = shape.centerZ;
        double rad = shape.radius;
        int segs = WIRE_SEGMENTS;

        // Three equatorial rings (XZ, XY, YZ planes)
        for (int i = 0; i < segs; i++) {
            double a1 = 2.0 * Math.PI * i / segs;
            double a2 = 2.0 * Math.PI * (i + 1) / segs;

            // XZ ring (horizontal)
            buf.vertex(matrix, (float)(cx + Math.cos(a1)*rad), (float)cy, (float)(cz + Math.sin(a1)*rad)).color(r, g, b, a).endVertex();
            buf.vertex(matrix, (float)(cx + Math.cos(a2)*rad), (float)cy, (float)(cz + Math.sin(a2)*rad)).color(r, g, b, a).endVertex();

            // XY ring (vertical, facing Z)
            buf.vertex(matrix, (float)(cx + Math.cos(a1)*rad), (float)(cy + Math.sin(a1)*rad), (float)cz).color(r, g, b, a).endVertex();
            buf.vertex(matrix, (float)(cx + Math.cos(a2)*rad), (float)(cy + Math.sin(a2)*rad), (float)cz).color(r, g, b, a).endVertex();

            // YZ ring (vertical, facing X)
            buf.vertex(matrix, (float)cx, (float)(cy + Math.cos(a1)*rad), (float)(cz + Math.sin(a1)*rad)).color(r, g, b, a).endVertex();
            buf.vertex(matrix, (float)cx, (float)(cy + Math.cos(a2)*rad), (float)(cz + Math.sin(a2)*rad)).color(r, g, b, a).endVertex();
        }

        BufferUploader.drawWithShader(buf.end());
    }

    // ==================== Helpers ====================

    private void line(BufferBuilder buf, Matrix4f matrix,
                      float x1, float y1, float z1, float x2, float y2, float z2,
                      float r, float g, float b, float a) {
        buf.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
    }
}
