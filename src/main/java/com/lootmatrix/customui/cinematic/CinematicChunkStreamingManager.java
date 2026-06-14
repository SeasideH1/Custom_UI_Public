package com.lootmatrix.customui.cinematic;

import com.lootmatrix.customui.Main;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Streams chunk packets around the cinematic camera position so the client can
 * render areas outside the player's normal tracking range.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CinematicChunkStreamingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CinematicChunkStreamingManager.class);
    private static final Map<UUID, ActiveStream> ACTIVE_STREAMS = new HashMap<>();

    private CinematicChunkStreamingManager() {}

    public static void start(ServerPlayer player, CameraPath path) {
        stop(player);
        if (player == null || path == null || path.getKeyframes().isEmpty() || !hasChunkStreaming(path)) {
            return;
        }

        ActiveStream stream = new ActiveStream(player, path);
        ACTIVE_STREAMS.put(player.getUUID(), stream);
        stream.syncChunks(player, true);
    }

    public static void stop(ServerPlayer player) {
        if (player == null) return;
        ActiveStream stream = ACTIVE_STREAMS.remove(player.getUUID());
        if (stream != null) {
            stream.close(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE_STREAMS.isEmpty() || event.getServer() == null) {
            return;
        }

        PlayerList playerList = event.getServer().getPlayerList();
        List<UUID> toRemove = new ArrayList<>();
        for (Map.Entry<UUID, ActiveStream> entry : ACTIVE_STREAMS.entrySet()) {
            ServerPlayer player = playerList.getPlayer(entry.getKey());
            ActiveStream stream = entry.getValue();

            if (player == null || player.connection == null) {
                toRemove.add(entry.getKey());
                continue;
            }

            try {
                if (!stream.tick(player)) {
                    stream.close(player);
                    toRemove.add(entry.getKey());
                }
            } catch (Exception exception) {
                LOGGER.warn("[CustomUI] Failed to stream cinematic chunks for player {}", player.getScoreboardName(), exception);
                stream.close(player);
                toRemove.add(entry.getKey());
            }
        }

        for (UUID uuid : toRemove) {
            ACTIVE_STREAMS.remove(uuid);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stop(player);
        }
    }

    private static boolean hasChunkStreaming(CameraPath path) {
        for (CameraKeyframe keyframe : path.getKeyframes()) {
            if (keyframe.sendChunks && keyframe.sendChunksRadius > 0) {
                return true;
            }
        }
        return false;
    }

    private static final class ActiveStream {
        private final CameraPath path;
        private final Vec3 playerOrigin;
        private final String dimensionName;
        private final Set<Long> streamedChunks = new HashSet<>();

        private int currentKeyframeIndex;
        private int ticksInSegment;
        private CameraPathSampler.Sample currentSample;

        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;
        private int lastRadius = -1;
        private boolean lastSendChunks;

        private ActiveStream(ServerPlayer player, CameraPath path) {
            this.path = path;
            this.playerOrigin = player.position();
            this.dimensionName = player.serverLevel().dimension().location().toString();
            this.currentSample = CameraPathSampler.sampleExact(path, 0, playerOrigin);
        }

        private boolean tick(ServerPlayer player) {
            if (!player.serverLevel().dimension().location().toString().equals(dimensionName)) {
                return false;
            }

            List<CameraKeyframe> keyframes = path.getKeyframes();
            if (keyframes.isEmpty()) {
                return false;
            }

            int nextIndex = CameraPathSampler.nextIndex(path, currentKeyframeIndex);
            if (nextIndex < 0) {
                return false;
            }

            CameraKeyframe current = keyframes.get(currentKeyframeIndex);
            if (current.durationTicks <= 0) {
                currentSample = CameraPathSampler.sampleExact(path, currentKeyframeIndex, playerOrigin);
                return false;
            }

            ticksInSegment++;
            float rawT = Math.min(1.0f, (float) ticksInSegment / current.durationTicks);
            currentSample = CameraPathSampler.sampleSegment(path, currentKeyframeIndex, rawT, playerOrigin);
            syncChunks(player, false);

            if (rawT >= 1.0f) {
                currentKeyframeIndex = nextIndex;
                ticksInSegment = 0;
                currentSample = CameraPathSampler.sampleExact(path, currentKeyframeIndex, playerOrigin);

                int nextPlayable = CameraPathSampler.nextIndex(path, currentKeyframeIndex);
                if (!path.isLoop() && (nextPlayable < 0 || keyframes.get(currentKeyframeIndex).durationTicks <= 0)) {
                    return false;
                }
            }
            return true;
        }

        private void close(ServerPlayer player) {
            if (player.connection == null) {
                streamedChunks.clear();
                lastSendChunks = false;
                lastChunkX = Integer.MIN_VALUE;
                lastChunkZ = Integer.MIN_VALUE;
                lastRadius = -1;
                return;
            }

            ChunkPos actualCenter = player.chunkPosition();
            int actualViewDistance = Math.max(player.server.getPlayerList().getViewDistance(), 0);
            player.connection.send(new ClientboundSetChunkCacheCenterPacket(actualCenter.x, actualCenter.z));
            player.connection.send(new ClientboundSetChunkCacheRadiusPacket(actualViewDistance));

            // During cinematic streaming the client cache center/radius may be moved far away
            // from the player. Vanilla tracking then assumes the original nearby chunks are still
            // present client-side and does not proactively resend them when we snap back. Re-send
            // the player's actual tracked area once so the client can immediately rebuild the
            // normal world view after the cinematic ends.
            resendActualTracking(player, actualCenter, actualViewDistance);

            for (long chunkKey : streamedChunks) {
                if (isOutsideActualTracking(chunkKey, actualCenter, actualViewDistance)) {
                    player.connection.send(new ClientboundForgetLevelChunkPacket(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)));
                }
            }
            streamedChunks.clear();
            lastSendChunks = false;
            lastChunkX = Integer.MIN_VALUE;
            lastChunkZ = Integer.MIN_VALUE;
            lastRadius = -1;
        }

        private void resendActualTracking(ServerPlayer player, ChunkPos actualCenter, int actualViewDistance) {
            ServerLevel level = player.serverLevel();
            ServerChunkCache chunkSource = level.getChunkSource();

            for (int dx = -actualViewDistance; dx <= actualViewDistance; dx++) {
                for (int dz = -actualViewDistance; dz <= actualViewDistance; dz++) {
                    int chunkX = actualCenter.x + dx;
                    int chunkZ = actualCenter.z + dz;
                    long chunkKey = ChunkPos.asLong(chunkX, chunkZ);

                    if (streamedChunks.contains(chunkKey)) {
                        continue;
                    }

                    ChunkAccess chunkAccess = chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
                    if (chunkAccess instanceof LevelChunk levelChunk) {
                        player.connection.send(new ClientboundLevelChunkWithLightPacket(
                                levelChunk,
                                chunkSource.getLightEngine(),
                                null,
                                null
                        ));
                    }
                }
            }
        }

        private void syncChunks(ServerPlayer player, boolean force) {
            if (player.connection == null || currentSample == null) {
                streamedChunks.clear();
                return;
            }

            if (!currentSample.sendChunks() || currentSample.sendChunksRadius() <= 0) {
                if (force || lastSendChunks || !streamedChunks.isEmpty()) {
                    close(player);
                }
                lastSendChunks = false;
                lastRadius = 0;
                return;
            }

            int centerChunkX = Mth.floor(currentSample.position().x) >> 4;
            int centerChunkZ = Mth.floor(currentSample.position().z) >> 4;
            int radius = currentSample.sendChunksRadius();

            if (!force && lastSendChunks && lastChunkX == centerChunkX && lastChunkZ == centerChunkZ && lastRadius == radius) {
                return;
            }

            ServerLevel level = player.serverLevel();
            ServerChunkCache chunkSource = level.getChunkSource();
            ChunkPos actualCenter = player.chunkPosition();
            int actualViewDistance = Math.max(player.server.getPlayerList().getViewDistance(), 0);

            if (force || lastChunkX != centerChunkX || lastChunkZ != centerChunkZ) {
                player.connection.send(new ClientboundSetChunkCacheCenterPacket(centerChunkX, centerChunkZ));
            }
            if (force || lastRadius != radius) {
                player.connection.send(new ClientboundSetChunkCacheRadiusPacket(radius));
            }

            Set<Long> targetChunks = new HashSet<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    long chunkKey = ChunkPos.asLong(chunkX, chunkZ);

                    if (!streamedChunks.contains(chunkKey)) {
                        ChunkAccess chunkAccess = chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
                        if (chunkAccess instanceof LevelChunk levelChunk) {
                            player.connection.send(new ClientboundLevelChunkWithLightPacket(
                                    levelChunk,
                                    chunkSource.getLightEngine(),
                                    null,
                                    null
                            ));
                        }
                    }
                    targetChunks.add(chunkKey);
                }
            }

            for (long chunkKey : streamedChunks) {
                if (!targetChunks.contains(chunkKey) && isOutsideActualTracking(chunkKey, actualCenter, actualViewDistance)) {
                    player.connection.send(new ClientboundForgetLevelChunkPacket(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)));
                }
            }

            streamedChunks.clear();
            streamedChunks.addAll(targetChunks);
            lastSendChunks = true;
            lastChunkX = centerChunkX;
            lastChunkZ = centerChunkZ;
            lastRadius = radius;
        }

        private boolean isOutsideActualTracking(long chunkKey, ChunkPos actualCenter, int actualViewDistance) {
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            return Math.abs(chunkPos.x - actualCenter.x) > actualViewDistance
                    || Math.abs(chunkPos.z - actualCenter.z) > actualViewDistance;
        }
    }
}
