package com.lootmatrix.customui.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import com.lootmatrix.customui.client.BackgroundGuard;

import javax.annotation.Nullable;
import java.util.*;

/**
 * VoidBarrier 世界级渲染器 - VBO优化版本
 * <p>
 * 性能优化：
 * 1. 使用 VertexBuffer (VBO) 缓存几何体，避免每帧重建顶点数据
 * 2. 增量式VBO重建 - 分帧处理避免卡顿
 * 3. 脏标记系统 - 只在方块变化时重建VBO
 * 4. 距离排序和数量限制 - 优先渲染近处方块
 * 5. 预分配缓冲区 - 减少GC压力
 * 6. 平滑过渡 - 进出渲染范围时避免突然的帧率下降
 */
@Mod.EventBusSubscriber(modid = "customui", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class VoidBarrierWorldRenderer {

    /** 遮挡体积延伸距离 */
    private static final float OCCLUSION_DEPTH = 256f;

    /** 搜索范围（方块距离） */
    private static final int BLOCK_SEARCH_RANGE = 64;

    /** 每帧最大渲染方块数 */
    private static final int MAX_RENDER_BLOCKS = 256;

    /** 内缩量，避免Z-fighting */
    private static final float INSET = 0.000f;

    /** 位置缓存更新间隔 */
    private static final long CACHE_INTERVAL_MS = 50;

    // ============== VBO 缓存系统 ==============

    /** 深度通道VBO */
    @Nullable
    private static VertexBuffer depthVBO = null;

    /** 颜色通道VBO */
    @Nullable
    private static VertexBuffer colorVBO = null;

    /** VBO是否需要重建 */
    private static boolean vboDirty = true;

    /** 上次VBO重建时的相机位置 */
    private static Vec3 lastVBOCameraPos = Vec3.ZERO;

    /** VBO重建的相机移动阈值（0.75格方块） */
    private static final double VBO_REBUILD_DISTANCE_SQ = 0.75 * 0.75;

    /** 上次VBO重建时的相机朝向（归一化） */
    private static float lastVBOYaw = 0f;
    private static float lastVBOPitch = 0f;

    /** VBO重建的视角变化阈值（度） */
    private static final float VBO_REBUILD_ANGLE_THRESHOLD = 8.0f;

    /** VBO重建最小帧间隔（纳秒）—— 60fps = 16.6667ms */
    private static final long VBO_REBUILD_MIN_INTERVAL_NS = 16_666_667L;

    /** 上次VBO重建时间（纳秒） */
    private static long lastVBORebuildTimeNs = 0;

    /** 区块首次加载时等待短暂静默窗口，合并连续方块更新，避免首帧反复重建。 */
    private static final long TRACKING_CHANGE_SETTLE_NS = 150_000_000L;

    /** 最近一次方块追踪集合变更时间（纳秒） */
    private static long lastTrackingChangeTimeNs = 0L;

    /** 上次构建VBO时使用的天空颜色 */
    private static int lastSkyR = -1, lastSkyG = -1, lastSkyB = -1;

    /** VBO中缓存的方块数量 */
    private static int cachedBlockCount = 0;

    // ============== 增量重建系统 ==============

    /** 每帧最大重建方块数（避免卡顿） */
    private static final int MAX_BLOCKS_PER_FRAME_REBUILD = 96;

    /** 正在进行增量重建 */
    private static boolean incrementalRebuildInProgress = false;

    /** 增量重建进度 */
    private static int incrementalRebuildIndex = 0;

    /** 增量重建目标位置列表 */
    private static final List<BlockPos> rebuildTargetPositions = new ArrayList<>();

    /** 增量重建的相机位置（内联 double，避免 Vec3 分配） */
    private static double rebuildCamX, rebuildCamY, rebuildCamZ;

    /** 增量重建的颜色 */
    private static int rebuildR = 0, rebuildG = 0, rebuildB = 0;

    /** 临时几何数据缓存 */
    @Nullable
    private static BufferBuilder.RenderedBuffer pendingDepthData = null;
    @Nullable
    private static BufferBuilder.RenderedBuffer pendingColorData = null;

    // ============== 方块位置缓存 ==============

    /** 缓存的方块位置（按距离排序后截取最近的方块） */
    private static final List<BlockPos> cachedPositions = new ArrayList<>(MAX_RENDER_BLOCKS);
    /** 临时列表用于排序，避免每次创建新对象 */
    private static final List<BlockPos> tempPositions = new ArrayList<>(512);
    /** 上次位置缓存更新时间 */
    private static long lastCacheTime = 0;
    /** 上次更新位置缓存时的相机位置 */
    private static Vec3 lastCacheCameraPos = Vec3.ZERO;
    /** 相机移动超过该阈值后才重新筛选/排序附近方块 */
    private static final double POSITION_CACHE_REBUILD_DISTANCE_SQ = 1.5 * 1.5;

    // ============== 事件驱动方块追踪 ==============

    /** 所有客户端加载的 VoidBarrier 位置（由 BlockEntity 生命周期维护） */
    private static final Set<BlockPos> trackedPositions = new HashSet<>();
    /** 追踪版本号，每次增删时递增 */
    private static int trackingVersion = 0;
    /** 上次处理的追踪版本 */
    private static int lastProcessedVersion = -1;

    /** 缓存的相机位置用于距离排序 */
    private static double sortCamX, sortCamY, sortCamZ;

    /** 预分配的顶点数组，避免GC */
    private static final float[][] VERTEX_BUFFER = new float[8][3];
    private static final float[][] VERTEX_BACK_BUFFER = new float[8][3];

    /** 面索引（静态常量，编译时优化） */
    private static final int[][] FACES = {
            {0, 3, 2, 1}, // -Z
            {4, 5, 6, 7}, // +Z
            {0, 4, 7, 3}, // -X
            {1, 2, 6, 5}, // +X
            {0, 1, 5, 4}, // -Y
            {3, 7, 6, 2}  // +Y
    };

    private static final float[][] FACE_NORMALS = {
            {0f, 0f, -1f},
            {0f, 0f, 1f},
            {-1f, 0f, 0f},
            {1f, 0f, 0f},
            {0f, -1f, 0f},
            {0f, 1f, 0f}
    };

    /** 边索引（静态常量） */
    private static final int[][] EDGES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private static final int[][] EDGE_FACE_PAIRS = {
            {0, 4}, {0, 3}, {0, 5}, {0, 2},
            {1, 4}, {1, 3}, {1, 5}, {1, 2},
            {2, 4}, {3, 4}, {3, 5}, {2, 5}
    };

    private static final boolean[] FACE_VISIBILITY_BUFFER = new boolean[FACES.length];

    /** 距离比较器（复用，避免lambda创建） */
    private static final Comparator<BlockPos> DISTANCE_COMPARATOR = (a, b) -> {
        double distA = distanceSquared(a);
        double distB = distanceSquared(b);
        return Double.compare(distA, distB);
    };

    /** 上次使用的维度 */
    private static String lastDimension = null;

    /** 上一帧是否有方块渲染（用于平滑过渡检测） */
    private static boolean hadBlocksLastFrame = false;

    private static final boolean DISABLED = false;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (DISABLED) return;

        // 在天空渲染之后、地形渲染之前介入
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        if (BackgroundGuard.shouldSkip()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        // 检查维度变化
        String currentDimension = level.dimension().location().toString();
        if (!currentDimension.equals(lastDimension)) {
            markDirty();
            lastDimension = currentDimension;
        }

        // 更新方块位置缓存（分散到多帧）
        long now = System.currentTimeMillis();
        if (shouldRefreshPositionCache(camPos, now)) {
            updatePositionCacheIncremental(camPos);
            lastCacheTime = now;
            lastCacheCameraPos = camPos;
        }

        // 处理增量重建
        if (incrementalRebuildInProgress) {
            continueIncrementalRebuild();
        }

        // 检查是否有方块需要渲染
        boolean hasBlocks = !cachedPositions.isEmpty();

        // 平滑过渡：如果刚刚从有方块变为无方块，不立即清理VBO
        if (!hasBlocks && !hadBlocksLastFrame) {
            cleanupVBOs();
            return;
        }
        hadBlocksLastFrame = hasBlocks;

        if (!hasBlocks) {
            return;
        }

        // 获取天空颜色
        Vec3 skyColor = level.getSkyColor(camPos, event.getPartialTick());
        int r = (int) (skyColor.x * 255);
        int g = (int) (skyColor.y * 255);
        int b = (int) (skyColor.z * 255);

        // 如果天空色太暗，使用雾色
        if (r + g + b < 30) {
            float[] fogColor = RenderSystem.getShaderFogColor();
            r = (int) (fogColor[0] * 255);
            g = (int) (fogColor[1] * 255);
            b = (int) (fogColor[2] * 255);
        }

        // 检查是否需要重建VBO（仅在没有增量重建进行时）
        if (!incrementalRebuildInProgress) {
            // 计算视角变化
            float currentYaw = camera.getYRot();
            float currentPitch = camera.getXRot();
            float deltaYaw = Math.abs(Mth.wrapDegrees(currentYaw - lastVBOYaw));
            float deltaPitch = Math.abs(Mth.wrapDegrees(currentPitch - lastVBOPitch));
            boolean angleChanged = deltaYaw > VBO_REBUILD_ANGLE_THRESHOLD
                    || deltaPitch > VBO_REBUILD_ANGLE_THRESHOLD;

            boolean needsRebuild = vboDirty
                    || depthVBO == null
                    || colorVBO == null
                    || camPos.distanceToSqr(lastVBOCameraPos) > VBO_REBUILD_DISTANCE_SQ
                    || angleChanged
                    || r != lastSkyR || g != lastSkyG || b != lastSkyB;

            if (needsRebuild) {
                // 帧间隔限制：最多60fps重建
                long nowNs = System.nanoTime();
                if (shouldDelayDirtyRebuild(nowNs)) {
                    return;
                }
                if (nowNs - lastVBORebuildTimeNs < VBO_REBUILD_MIN_INTERVAL_NS
                        && depthVBO != null && colorVBO != null && !vboDirty) {
                    // 跳过本帧重建，还没到最小间隔
                } else {
                    // 当前增量路径无法保留 BufferBuilder 中间态，真正分帧前避免触发伪增量重建。
                    rebuildVBOsImmediate(camPos.x, camPos.y, camPos.z, r, g, b);
                    lastVBOCameraPos = camPos;
                    lastVBOYaw = currentYaw;
                    lastVBOPitch = currentPitch;
                    lastSkyR = r;
                    lastSkyG = g;
                    lastSkyB = b;
                    vboDirty = false;
                    lastVBORebuildTimeNs = nowNs;
                }
            }
        }

        // 使用VBO渲染（即使正在增量重建，也使用旧VBO）
        if (depthVBO != null && colorVBO != null && cachedBlockCount > 0) {
            renderWithVBOs(event.getPoseStack(), camPos);
        }
    }

    /**
     * 开始增量重建
     */
    private static void startIncrementalRebuild(double camX, double camY, double camZ, int r, int g, int b) {
        incrementalRebuildInProgress = true;
        incrementalRebuildIndex = 0;
        rebuildTargetPositions.clear();
        rebuildTargetPositions.addAll(cachedPositions);
        rebuildCamX = camX;
        rebuildCamY = camY;
        rebuildCamZ = camZ;
        rebuildR = r;
        rebuildG = g;
        rebuildB = b;
        pendingDepthData = null;
        pendingColorData = null;
    }

    /**
     * 继续增量重建
     */
    private static void continueIncrementalRebuild() {
        if (!incrementalRebuildInProgress || rebuildTargetPositions.isEmpty()) {
            incrementalRebuildInProgress = false;
            return;
        }

        int startIndex = incrementalRebuildIndex;
        int endIndex = Math.min(startIndex + MAX_BLOCKS_PER_FRAME_REBUILD, rebuildTargetPositions.size());

        // 如果是第一帧，开始构建
        if (startIndex == 0) {
            // 不立即清理旧VBO，让它继续渲染直到新VBO准备好
        }

        // 构建这一帧的几何体
        boolean isFirstChunk = (startIndex == 0);
        boolean isLastChunk = (endIndex >= rebuildTargetPositions.size());

        if (isFirstChunk) {
            // 开始新的Buffer
            BufferBuilder depthBuilder = Tesselator.getInstance().getBuilder();
            depthBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            for (int i = 0; i < endIndex; i++) {
                BlockPos pos = rebuildTargetPositions.get(i);
                buildDepthGeometry(depthBuilder, pos, rebuildCamX, rebuildCamY, rebuildCamZ);
            }

            if (isLastChunk) {
                // 一帧完成
                finishRebuild(depthBuilder, rebuildR, rebuildG, rebuildB);
            } else {
                // 需要更多帧 - 暂存数据
                // 注意：由于BufferBuilder的限制，我们需要在单帧内完成
                // 所以这里改为分批但在同一帧完成
                for (int i = endIndex; i < rebuildTargetPositions.size(); i++) {
                    BlockPos pos = rebuildTargetPositions.get(i);
                    buildDepthGeometry(depthBuilder, pos, rebuildCamX, rebuildCamY, rebuildCamZ);
                }
                finishRebuild(depthBuilder, rebuildR, rebuildG, rebuildB);
            }
        }

        incrementalRebuildIndex = endIndex;
        if (incrementalRebuildIndex >= rebuildTargetPositions.size()) {
            incrementalRebuildInProgress = false;
            rebuildTargetPositions.clear();
        }
    }

    /**
     * 完成重建并上传VBO
     */
    private static void finishRebuild(BufferBuilder depthBuilder, int r, int g, int b) {
        BufferBuilder.RenderedBuffer depthData = depthBuilder.end();

        // 构建颜色通道
        BufferBuilder colorBuilder = Tesselator.getInstance().getBuilder();
        colorBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (BlockPos pos : rebuildTargetPositions) {
            buildColorGeometry(colorBuilder, pos, rebuildCamX, rebuildCamY, rebuildCamZ, r, g, b);
        }

        BufferBuilder.RenderedBuffer colorData = colorBuilder.end();

        // 复用 VBO（避免反复 glGenBuffers/glDeleteBuffers）
        ensureVBOs();
        depthVBO.bind();
        depthVBO.upload(depthData);

        colorVBO.bind();
        colorVBO.upload(colorData);
        VertexBuffer.unbind();

        cachedBlockCount = rebuildTargetPositions.size();
        incrementalRebuildInProgress = false;
    }

    private static boolean shouldRefreshPositionCache(Vec3 camPos, long now) {
        if (trackingVersion != lastProcessedVersion || cachedPositions.isEmpty()) {
            return true;
        }
        if (now - lastCacheTime <= CACHE_INTERVAL_MS) {
            return false;
        }
        return camPos.distanceToSqr(lastCacheCameraPos) > POSITION_CACHE_REBUILD_DISTANCE_SQ;
    }

    /**
     * 构建单个方块的几何体（内联 Vec3 运算，避免 3 个短命对象/方块）
     */
    private static void buildDepthGeometry(BufferBuilder builder, BlockPos pos,
                                           double camX, double camY, double camZ) {
        double dx = camX - (pos.getX() + 0.5);
        double dy = camY - (pos.getY() + 0.5);
        double dz = camZ - (pos.getZ() + 0.5);
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 0.01) return;
        double invLen = 1.0 / Math.sqrt(lenSq);

        buildDepthOcclusionGeometry(builder, pos,
                (float) (dx * invLen), (float) (dy * invLen), (float) (dz * invLen));
    }

    /**
     * 颜色通道只绘制实际可见的方块表面，避免背面和体积侧面漏到前景缝隙里。
     */
    private static void buildColorGeometry(BufferBuilder builder, BlockPos pos,
                                           double camX, double camY, double camZ,
                                           int r, int g, int b) {
        double dx = camX - (pos.getX() + 0.5);
        double dy = camY - (pos.getY() + 0.5);
        double dz = camZ - (pos.getZ() + 0.5);
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 0.01) return;
        double invLen = 1.0 / Math.sqrt(lenSq);

        buildVisibleFaceGeometry(builder, pos,
                (float) (dx * invLen), (float) (dy * invLen), (float) (dz * invLen),
                r, g, b);
    }

    /**
     * 立即重建VBO（用于小数量方块）
     */
    private static void rebuildVBOsImmediate(double camX, double camY, double camZ, int r, int g, int b) {
        if (cachedPositions.isEmpty()) {
            cachedBlockCount = 0;
            return;
        }

        // 构建深度通道几何体
        BufferBuilder depthBuilder = Tesselator.getInstance().getBuilder();
        depthBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (BlockPos pos : cachedPositions) {
            buildDepthGeometry(depthBuilder, pos, camX, camY, camZ);
        }

        BufferBuilder.RenderedBuffer depthData = depthBuilder.end();

        // 构建颜色通道几何体
        BufferBuilder colorBuilder = Tesselator.getInstance().getBuilder();
        colorBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (BlockPos pos : cachedPositions) {
            buildColorGeometry(colorBuilder, pos, camX, camY, camZ, r, g, b);
        }

        BufferBuilder.RenderedBuffer colorData = colorBuilder.end();

        // 复用 VBO（避免反复 glGenBuffers/glDeleteBuffers）
        ensureVBOs();
        depthVBO.bind();
        depthVBO.upload(depthData);

        colorVBO.bind();
        colorVBO.upload(colorData);
        VertexBuffer.unbind();

        cachedBlockCount = cachedPositions.size();
    }

    /**
     * 使用VBO进行渲染
     */
    private static void renderWithVBOs(PoseStack poseStack, Vec3 camPos) {
        if (depthVBO == null || colorVBO == null || cachedBlockCount == 0) return;

        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) return;

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f modelViewMatrix = poseStack.last().pose();
        Matrix4f projectionMatrix = RenderSystem.getProjectionMatrix();

        // 设置渲染状态
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.disableBlend();

        // 第一遍：仅写入深度
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0f, -1.0f);

        try {
            depthVBO.bind();
            depthVBO.drawWithShader(modelViewMatrix, projectionMatrix, shader);
            VertexBuffer.unbind();

            RenderSystem.disablePolygonOffset();

            // 第二遍：只填充颜色（深度相等处）
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(false);
            RenderSystem.depthFunc(GL11.GL_EQUAL);

            colorVBO.bind();
            colorVBO.drawWithShader(modelViewMatrix, projectionMatrix, shader);
            VertexBuffer.unbind();
        } finally {
            // 即使中途异常也必须复位：否则 colorMask=false / depthFunc=EQUAL 会让全屏不可见，
            // depthMask=false 会让世界深度无法写入。
            RenderSystem.disablePolygonOffset();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableCull();
            poseStack.popPose();
        }
    }

    /**
     * 更新方块位置缓存（事件驱动：使用 trackedPositions 代替全量区块扫描）
     */
    private static void updatePositionCacheIncremental(Vec3 camPos) {
        // 检查追踪版本是否变化（方块增删时版本号递增）
        if (trackingVersion != lastProcessedVersion) {
            lastProcessedVersion = trackingVersion;
            vboDirty = true;
        }

        // 缓存相机位置用于距离计算
        sortCamX = camPos.x;
        sortCamY = camPos.y;
        sortCamZ = camPos.z;

        // 预计算距离平方阈值
        double maxDistSq = (double) BLOCK_SEARCH_RANGE * BLOCK_SEARCH_RANGE;

        // 从追踪集合中过滤范围内的方块（O(N) 遍历已追踪方块，无区块扫描）
        tempPositions.clear();
        for (BlockPos pos : trackedPositions) {
            if (distanceSquared(pos) <= maxDistSq) {
                tempPositions.add(pos);
            }
        }

        // 按距离排序并截取最近的方块
        cachedPositions.clear();
        if (tempPositions.size() > MAX_RENDER_BLOCKS) {
            tempPositions.sort(DISTANCE_COMPARATOR);
            for (int i = 0; i < MAX_RENDER_BLOCKS; i++) {
                cachedPositions.add(tempPositions.get(i));
            }
        } else {
            cachedPositions.addAll(tempPositions);
        }
    }

    /** 计算方块中心到相机的距离平方（避免开方） */
    private static double distanceSquared(BlockPos pos) {
        double dx = pos.getX() + 0.5 - sortCamX;
        double dy = pos.getY() + 0.5 - sortCamY;
        double dz = pos.getZ() + 0.5 - sortCamZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 构建遮挡体积几何数据（世界坐标）
     */
    private static void buildOcclusionGeometry(
            BufferBuilder builder, BlockPos pos,
            float dirX, float dirY, float dirZ,
            int r, int g, int b
    ) {
        float x = pos.getX();
        float y = pos.getY();
        float z = pos.getZ();

        // 延伸方向（相机反方向）
        float extX = -dirX * OCCLUSION_DEPTH;
        float extY = -dirY * OCCLUSION_DEPTH;
        float extZ = -dirZ * OCCLUSION_DEPTH;

        // 方块的8个顶点
        float minX = x + INSET;
        float minY = y + INSET;
        float minZ = z + INSET;
        float maxX = x + 1 - INSET;
        float maxY = y + 1 - INSET;
        float maxZ = z + 1 - INSET;

        // 使用预分配的顶点缓冲区
        float[][] v = VERTEX_BUFFER;
        v[0][0] = minX; v[0][1] = minY; v[0][2] = minZ;
        v[1][0] = maxX; v[1][1] = minY; v[1][2] = minZ;
        v[2][0] = maxX; v[2][1] = maxY; v[2][2] = minZ;
        v[3][0] = minX; v[3][1] = maxY; v[3][2] = minZ;
        v[4][0] = minX; v[4][1] = minY; v[4][2] = maxZ;
        v[5][0] = maxX; v[5][1] = minY; v[5][2] = maxZ;
        v[6][0] = maxX; v[6][1] = maxY; v[6][2] = maxZ;
        v[7][0] = minX; v[7][1] = maxY; v[7][2] = maxZ;

        // 延伸后的顶点
        float[][] vb = VERTEX_BACK_BUFFER;
        for (int i = 0; i < 8; i++) {
            vb[i][0] = v[i][0] + extX;
            vb[i][1] = v[i][1] + extY;
            vb[i][2] = v[i][2] + extZ;
        }

        // 渲染前面（方块本身的6个面）
        for (int[] face : FACES) {
            for (int idx : face) {
                builder.vertex(v[idx][0], v[idx][1], v[idx][2])
                        .color(r, g, b, 255).endVertex();
            }
        }

        // 渲染后面（延伸后的6个面，反向）
        for (int[] face : FACES) {
            for (int i = 3; i >= 0; i--) {
                int idx = face[i];
                builder.vertex(vb[idx][0], vb[idx][1], vb[idx][2])
                        .color(r, g, b, 255).endVertex();
            }
        }

        // 渲染连接的侧面
        for (int[] edge : EDGES) {
            int i = edge[0], j = edge[1];
            builder.vertex(v[i][0], v[i][1], v[i][2]).color(r, g, b, 255).endVertex();
            builder.vertex(v[j][0], v[j][1], v[j][2]).color(r, g, b, 255).endVertex();
            builder.vertex(vb[j][0], vb[j][1], vb[j][2]).color(r, g, b, 255).endVertex();
            builder.vertex(vb[i][0], vb[i][1], vb[i][2]).color(r, g, b, 255).endVertex();
        }
    }

    /**
     * 标记VBO为脏，需要重建
     */
    private static void buildDepthOcclusionGeometry(
            BufferBuilder builder, BlockPos pos,
            float dirX, float dirY, float dirZ
    ) {
        populateBlockVertices(pos);

        float extX = -dirX * OCCLUSION_DEPTH;
        float extY = -dirY * OCCLUSION_DEPTH;
        float extZ = -dirZ * OCCLUSION_DEPTH;

        float[][] v = VERTEX_BUFFER;
        float[][] vb = VERTEX_BACK_BUFFER;
        for (int i = 0; i < 8; i++) {
            vb[i][0] = v[i][0] + extX;
            vb[i][1] = v[i][1] + extY;
            vb[i][2] = v[i][2] + extZ;
        }

        updateFaceVisibility(dirX, dirY, dirZ);

        for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
            if (FACE_VISIBILITY_BUFFER[faceIndex]) {
                emitFace(builder, v, FACES[faceIndex], false, 0, 0, 0);
            }
        }

        for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
            if (!FACE_VISIBILITY_BUFFER[faceIndex]) {
                emitFace(builder, vb, FACES[faceIndex], true, 0, 0, 0);
            }
        }

        for (int edgeIndex = 0; edgeIndex < EDGES.length; edgeIndex++) {
            int faceA = EDGE_FACE_PAIRS[edgeIndex][0];
            int faceB = EDGE_FACE_PAIRS[edgeIndex][1];
            if (FACE_VISIBILITY_BUFFER[faceA] == FACE_VISIBILITY_BUFFER[faceB]) {
                continue;
            }

            int i = EDGES[edgeIndex][0];
            int j = EDGES[edgeIndex][1];
            builder.vertex(v[i][0], v[i][1], v[i][2]).color(0, 0, 0, 255).endVertex();
            builder.vertex(v[j][0], v[j][1], v[j][2]).color(0, 0, 0, 255).endVertex();
            builder.vertex(vb[j][0], vb[j][1], vb[j][2]).color(0, 0, 0, 255).endVertex();
            builder.vertex(vb[i][0], vb[i][1], vb[i][2]).color(0, 0, 0, 255).endVertex();
        }
    }

    private static boolean shouldDelayDirtyRebuild(long nowNs) {
        return vboDirty
                && trackingVersion != lastProcessedVersion
                && nowNs - lastTrackingChangeTimeNs < TRACKING_CHANGE_SETTLE_NS;
    }

    private static void buildVisibleFaceGeometry(
            BufferBuilder builder, BlockPos pos,
            float dirX, float dirY, float dirZ,
            int r, int g, int b
    ) {
        populateBlockVertices(pos);
        updateFaceVisibility(dirX, dirY, dirZ);

        for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
            if (FACE_VISIBILITY_BUFFER[faceIndex]) {
                emitFace(builder, VERTEX_BUFFER, FACES[faceIndex], false, r, g, b);
            }
        }
    }

    private static void populateBlockVertices(BlockPos pos) {
        float x = pos.getX();
        float y = pos.getY();
        float z = pos.getZ();

        float minX = x + INSET;
        float minY = y + INSET;
        float minZ = z + INSET;
        float maxX = x + 1 - INSET;
        float maxY = y + 1 - INSET;
        float maxZ = z + 1 - INSET;

        float[][] v = VERTEX_BUFFER;
        v[0][0] = minX; v[0][1] = minY; v[0][2] = minZ;
        v[1][0] = maxX; v[1][1] = minY; v[1][2] = minZ;
        v[2][0] = maxX; v[2][1] = maxY; v[2][2] = minZ;
        v[3][0] = minX; v[3][1] = maxY; v[3][2] = minZ;
        v[4][0] = minX; v[4][1] = minY; v[4][2] = maxZ;
        v[5][0] = maxX; v[5][1] = minY; v[5][2] = maxZ;
        v[6][0] = maxX; v[6][1] = maxY; v[6][2] = maxZ;
        v[7][0] = minX; v[7][1] = maxY; v[7][2] = maxZ;
    }

    private static void updateFaceVisibility(float dirX, float dirY, float dirZ) {
        for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
            float[] normal = FACE_NORMALS[faceIndex];
            FACE_VISIBILITY_BUFFER[faceIndex] =
                    normal[0] * dirX + normal[1] * dirY + normal[2] * dirZ > 0.0f;
        }
    }

    private static void emitFace(BufferBuilder builder, float[][] vertices, int[] face,
                                 boolean reverse, int r, int g, int b) {
        if (reverse) {
            for (int i = face.length - 1; i >= 0; i--) {
                int idx = face[i];
                builder.vertex(vertices[idx][0], vertices[idx][1], vertices[idx][2])
                        .color(r, g, b, 255).endVertex();
            }
            return;
        }

        for (int idx : face) {
            builder.vertex(vertices[idx][0], vertices[idx][1], vertices[idx][2])
                    .color(r, g, b, 255).endVertex();
        }
    }

    public static void markDirty() {
        vboDirty = true;
    }

    /**
     * 懒初始化 VBO（单次创建，后续复用，避免每次重建的 GL 开销）
     */
    private static void ensureVBOs() {
        if (depthVBO == null) {
            depthVBO = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        if (colorVBO == null) {
            colorVBO = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
    }

    /**
     * 方块被放置/加载时由 BlockEntity.onLoad() 调用
     */
    public static void onBlockAdded(BlockPos pos) {
        if (trackedPositions.add(pos)) {
            trackingVersion++;
            vboDirty = true;
            lastTrackingChangeTimeNs = System.nanoTime();
        }
    }

    /**
     * 方块被破坏/卸载时由 BlockEntity.setRemoved() 调用
     */
    public static void onBlockRemoved(BlockPos pos) {
        if (trackedPositions.remove(pos)) {
            trackingVersion++;
            vboDirty = true;
            lastTrackingChangeTimeNs = System.nanoTime();
        }
    }

    /**
     * 清理VBO资源
     */
    private static void cleanupVBOs() {
        // VBO 保留复用，仅重置计数
        cachedBlockCount = 0;
        incrementalRebuildInProgress = false;
    }

    /**
     * 清除所有缓存
     */
    public static void clearCache() {
        cachedPositions.clear();
        tempPositions.clear();
        trackedPositions.clear();
        rebuildTargetPositions.clear();
        lastCacheTime = 0;
        lastDimension = null;
        vboDirty = true;
        incrementalRebuildInProgress = false;
        lastVBORebuildTimeNs = 0;
        lastVBOYaw = 0f;
        lastVBOPitch = 0f;
        lastProcessedVersion = -1;
        trackingVersion = 0;
        lastTrackingChangeTimeNs = 0L;
        // 完全清理时释放 VBO GPU 资源
        if (depthVBO != null) { depthVBO.close(); depthVBO = null; }
        if (colorVBO != null) { colorVBO.close(); colorVBO = null; }
        cachedBlockCount = 0;
    }
}
