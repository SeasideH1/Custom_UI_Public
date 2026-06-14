package com.lootmatrix.customui.server;

import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.network.ScoreboardOverlayConfigPacket;
import com.lootmatrix.customui.network.ScoreboardOverlayUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side tick handler for the scoreboard overlay.
 * Reads scoreboard objectives each tick, computes delta, and sends minimal packets.
 */
public class ScoreboardOverlayTickHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScoreboardOverlayTickHandler.class);
    private static boolean registered = false;

    public static void register() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(new ScoreboardOverlayTickHandler());
            registered = true;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ScoreboardOverlayData data = ScoreboardOverlayData.get(server);
        if (data == null) return;

        Scoreboard scoreboard = server.getScoreboard();

        // Read current values - prefer direct values over scoreboard bindings
        // Team A Progress
        if (data.teamADirectProgress >= 0f) {
            data.teamAProgress = data.teamADirectProgress;
        } else {
            data.teamAProgress = readProgress(scoreboard, data.teamAProgressHolder, data.teamAProgressObjective,
                    data.teamAMaxHolder, data.teamAMaxObjective);
        }

        // Team A Score
        if (data.teamADirectScore >= 0) {
            data.teamAScore = data.teamADirectScore;
        } else {
            data.teamAScore = readScore(scoreboard, data.teamAScoreHolder, data.teamAScoreObjective);
        }

        // Team B Progress
        if (data.teamBDirectProgress >= 0f) {
            data.teamBProgress = data.teamBDirectProgress;
        } else {
            data.teamBProgress = readProgress(scoreboard, data.teamBProgressHolder, data.teamBProgressObjective,
                    data.teamBMaxHolder, data.teamBMaxObjective);
        }

        // Team B Score
        if (data.teamBDirectScore >= 0) {
            data.teamBScore = data.teamBDirectScore;
        } else {
            data.teamBScore = readScore(scoreboard, data.teamBScoreHolder, data.teamBScoreObjective);
        }

        // Timer
        if (data.timerDirectTicks >= 0) {
            data.timerTicks = data.timerDirectTicks;
            if (data.timerDirectTicks > 0) {
                data.timerDirectTicks--;
            }
        } else {
            data.timerTicks = readScore(scoreboard, data.timerHolder, data.timerObjective);
        }

        // Handle temp color countdown on server side
        if (!data.timerColorSwitch && data.timerTempDuration > 0) {
            data.timerTempDuration--;
            if (data.timerTempDuration == 0) {
                data.markConfigChanged(); // Trigger resync when color reverts
            }
        }

        // Check if config needs full resync
        boolean configChanged = data.configVersion != data.lastSyncedConfigVersion;

        // Compute delta mask for value changes
        byte deltaMask = data.getDeltaMask();

        // Send packets to visible players
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean visible = data.isVisibleTo(player.getUUID());
            Integer syncedVersion = data.playerSyncedConfigVersion.get(player.getUUID());
            Boolean lastVisibleState = data.playerLastVisibleState.get(player.getUUID());
            boolean visibilityChanged = lastVisibleState == null || lastVisibleState != visible;
            boolean playerNeedsConfig = syncedVersion == null || syncedVersion != data.configVersion || visibilityChanged;

            if (configChanged || playerNeedsConfig) {
                // Full config sync
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new ScoreboardOverlayConfigPacket(
                                data.teamAIconPath, data.teamABarColor,
                                data.teamBIconPath, data.teamBBarColor,
                                data.glowMode, visible,
                                data.timerColor, data.timerTempColor,
                                data.timerTempDuration, data.timerColorSwitch,
                                data.reverseFillDirection
                        )
                );
                data.playerSyncedConfigVersion.put(player.getUUID(), data.configVersion);
                data.playerLastVisibleState.put(player.getUUID(), visible);
            }

            // Delta update (only if something changed and player should see it)
            if (visible && deltaMask != 0) {
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new ScoreboardOverlayUpdatePacket(
                                deltaMask,
                                data.teamAProgress, data.teamAScore,
                                data.teamBProgress, data.teamBScore,
                                data.timerTicks
                        )
                );
            }
        }

        // Snapshot for next tick's delta detection
        data.snapshotPrevious();
        if (configChanged) {
            data.lastSyncedConfigVersion = data.configVersion;
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        ScoreboardOverlayData data = ScoreboardOverlayData.get(server);
        if (data == null) return;

        boolean visible = data.isVisibleTo(player.getUUID());

        // Send full config
        ModNetworkHandler.INSTANCE.send(
                PacketDistributor.PLAYER.with(() -> player),
                new ScoreboardOverlayConfigPacket(
                        data.teamAIconPath, data.teamABarColor,
                        data.teamBIconPath, data.teamBBarColor,
                        data.glowMode, visible,
                        data.timerColor, data.timerTempColor,
                        data.timerTempDuration, data.timerColorSwitch,
                        data.reverseFillDirection
                )
        );
        data.playerSyncedConfigVersion.put(player.getUUID(), data.configVersion);
        data.playerLastVisibleState.put(player.getUUID(), visible);

        // Send full current values
        if (visible) {
            ModNetworkHandler.INSTANCE.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ScoreboardOverlayUpdatePacket(
                            (byte) 0x1F, // all fields
                            data.teamAProgress, data.teamAScore,
                            data.teamBProgress, data.teamBScore,
                            data.timerTicks
                    )
            );
        }
    }

    /**
     * Read progress as value/maxValue from scoreboard objectives.
     */
    private float readProgress(Scoreboard scoreboard, String holder, String objectiveName,
                                String maxHolder, String maxObjectiveName) {
        if (holder.isEmpty() || objectiveName.isEmpty()) return 0f;
        int value = readScore(scoreboard, holder, objectiveName);
        int maxValue = readScore(scoreboard, maxHolder, maxObjectiveName);
        if (maxValue <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (float) value / maxValue));
    }

    /**
     * Read a single scoreboard score.
     */
    private int readScore(Scoreboard scoreboard, String holder, String objectiveName) {
        if (holder.isEmpty() || objectiveName.isEmpty()) return 0;
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) return 0;
        if (!scoreboard.hasPlayerScore(holder, objective)) return 0;
        return scoreboard.getOrCreatePlayerScore(holder, objective).getScore();
    }
}
