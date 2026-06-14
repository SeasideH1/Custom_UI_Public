package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.config.TeamIndicatorConfig;
import com.lootmatrix.customui.registry.ModEffects;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side state manager for Team Glow effect.
 *
 * Tracks which players should glow. The actual glow rendering is done by:
 * - EntityGlowMixin: makes isCurrentlyGlowing() return true for TeamGlow players
 * - EntityTeamColorMixin: makes getTeamColor() return green for TeamGlow players
 *
 * Performance optimizations:
 * - Frustum culling - only process entities in view
 * - Distance-based culling - skip entities beyond render distance
 * - FOV-based early rejection - quick check before expensive operations
 * - Tick-level position sync - updates every tick for smooth tracking
 * - Render snapshot - thread-safe rendering without tick interference
 * - Caches effect registry lookup
 * - Uses efficient Set operations with pre-sized collections
 */
@Mod.EventBusSubscriber(
        modid = Main.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public class TeamGlowRenderer {

    // ============== 性能优化参数 ==============

    /** 最大发光玩家距离（方块）- 从配置读取 */
    private static double getMaxGlowDistance() {
        return TeamIndicatorConfig.INSTANCE.maxDistance.get();
    }

    /** 最大处理玩家数量 - 从配置读取 */
    private static int getMaxPlayersToProcess() {
        return TeamIndicatorConfig.INSTANCE.maxPlayersToProcess.get();
    }

    // ============== 玩家追踪集合 ==============

    /** Players that should glow for the local viewer (主集合，每tick更新) */
    private static final Set<UUID> TEAM_GLOW_PLAYERS = new HashSet<>(64);

    /** 渲染帧时使用的快照（避免在渲染时修改） */
    private static volatile Set<UUID> renderSnapshot = Collections.emptySet();

    /** 标记快照是否需要更新 */
    private static volatile boolean snapshotDirty = true;

    /** Players the server told us have TeamGlow effect (Mohist sync) */
    private static final Set<UUID> SERVER_GLOW_PLAYERS = ConcurrentHashMap.newKeySet(64);

    public static int getGlowColor() {
        return TeamIndicatorConfig.INSTANCE.glowColor.get();
    }

    /** Cached team glow effect - avoids repeated registry lookup */
    @Nullable
    private static MobEffect cachedTeamGlowEffect = null;
    private static boolean effectCacheInitialized = false;

    /** 上一帧的相机位置（用于检测相机移动） */
    private static Vec3 lastCameraPos = Vec3.ZERO;

    // ============== 事件处理 ==============

    /**
     * 在实体渲染之前更新快照，确保发光效果正确应用
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // 在实体渲染之前更新快照，而不是之后
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;

        // Check if team glow is enabled in config
        if (!TeamIndicatorConfig.INSTANCE.enabled.get()) {
            // Clear glow players when disabled
            TEAM_GLOW_PLAYERS.clear();
            renderSnapshot = Collections.emptySet();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null || mc.level == null || mc.player == null) return;

        // 立即更新发光玩家列表（确保渲染前数据是最新的）
        updateGlowingPlayersForRender(mc, event.getCamera());

        // 更新快照供渲染使用
        renderSnapshot = TEAM_GLOW_PLAYERS.isEmpty() ? Collections.emptySet() : Set.copyOf(TEAM_GLOW_PLAYERS);
        snapshotDirty = false;
    }

    /**
     * 每tick更新发光玩家列表（保证实时位置同步）
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (BackgroundGuard.shouldSkip()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            TEAM_GLOW_PLAYERS.clear();
            SERVER_GLOW_PLAYERS.clear();
            effectCacheInitialized = false;
            snapshotDirty = true;
            BackgroundGuard.reset();
            return;
        }

        // Cache the effect lookup once
        if (!effectCacheInitialized) {
            var reg = ModEffects.TEAM_GLOW;
            cachedTeamGlowEffect = reg.isPresent() ? reg.get() : null;
            effectCacheInitialized = true;
        }

        snapshotDirty = true;
    }

    /**
     * 为渲染更新发光玩家列表（在渲染前调用）
     */
    private static void updateGlowingPlayersForRender(Minecraft mc, Camera camera) {
        TEAM_GLOW_PLAYERS.clear();

        // 确保效果已初始化
        if (!effectCacheInitialized) {
            var reg = ModEffects.TEAM_GLOW;
            cachedTeamGlowEffect = reg.isPresent() ? reg.get() : null;
            effectCacheInitialized = true;
        }

        if (cachedTeamGlowEffect == null && SERVER_GLOW_PLAYERS.isEmpty()) {
            return;
        }

        Player localPlayer = mc.player;
        boolean isFirstPerson = mc.options.getCameraType().isFirstPerson();
        PlayerTeam localTeam = localPlayer.getTeam() instanceof PlayerTeam pt ? pt : null;
        String localTeamName = localTeam != null ? localTeam.getName() : null;
        boolean localTeamless = localTeamName == null;

        Vec3 cameraPos = camera.getPosition();
        lastCameraPos = cameraPos;

        double maxDistSq = getMaxGlowDistance() * getMaxGlowDistance();
        int maxPlayers = getMaxPlayersToProcess();

        int processedCount = 0;
        for (Player player : mc.level.players()) {
            // 限制处理数量
            if (processedCount >= maxPlayers) break;

            // 快速距离检查
            double distSq = player.distanceToSqr(cameraPos);
            if (distSq > maxDistSq) {
                continue;
            }

            // Check if has effect (either local or server-synced)
            boolean hasEffect = false;
            if (cachedTeamGlowEffect != null) {
                hasEffect = player.hasEffect(cachedTeamGlowEffect);
            }
            boolean hasServerGlow = SERVER_GLOW_PLAYERS.contains(player.getUUID());

            if (!hasEffect && !hasServerGlow) {
                continue;
            }

            processedCount++;

            // Skip if has vanilla glowing
            if (player.hasEffect(MobEffects.GLOWING)) {
                continue;
            }

            // Skip first-person self
            if (player == localPlayer) {
                if (isFirstPerson) {
                    continue;
                }
            } else {
                // 队伍检查
                PlayerTeam targetTeam = player.getTeam() instanceof PlayerTeam pt ? pt : null;
                if (localTeamless) {
                    if (targetTeam != null) {
                        continue;
                    }
                } else {
                    if (targetTeam == null || !localTeamName.equals(targetTeam.getName())) {
                        continue;
                    }
                }
            }

            // 不进行视锥剔除 - 让发光效果始终可见（即使实体在视野外，发光轮廓也应该可见）
            TEAM_GLOW_PLAYERS.add(player.getUUID());
        }
    }

    /**
     * 更新发光玩家列表（每tick调用 - 保持兼容性）
     */
    private static void updateGlowingPlayers(Minecraft mc) {
        // 现在主要更新在渲染阶段进行，tick只做基础检查
    }

    // ============== 服务器同步 ==============

    public static void setServerGlow(UUID targetId, boolean hasGlow) {
        if (hasGlow) {
            SERVER_GLOW_PLAYERS.add(targetId);
        } else {
            SERVER_GLOW_PLAYERS.remove(targetId);
            // Immediately remove from glow set when server says effect ended
            TEAM_GLOW_PLAYERS.remove(targetId);
            snapshotDirty = true;
        }
    }

    // ============== 查询方法（被Mixin调用） ==============

    public static boolean shouldRenderNameTag(Player player) {
        if (player == null) return false;
        return renderSnapshot.contains(player.getUUID());
    }

    /**
     * Called by EntityGlowMixin to check if player should glow.
     * This is called very frequently during rendering, so it must be fast.
     */
    public static boolean hasTeamGlow(Player player) {
        if (player == null) return false;
        return renderSnapshot.contains(player.getUUID());
    }

    /**
     * Check if any players are currently glowing (for early exit optimization)
     */
    public static boolean hasAnyGlowingPlayers() {
        return !renderSnapshot.isEmpty();
    }

    /**
     * Get the number of currently glowing players
     */
    public static int getGlowingPlayerCount() {
        return renderSnapshot.size();
    }

    /**
     * Clear all state (for cleanup on disconnect)
     */
    public static void clearAll() {
        TEAM_GLOW_PLAYERS.clear();
        SERVER_GLOW_PLAYERS.clear();
        renderSnapshot = Collections.emptySet();
        effectCacheInitialized = false;
        snapshotDirty = true;
    }
}

