package com.lootmatrix.customui.server;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.network.TeamGlowSyncPacket;
import com.lootmatrix.customui.registry.ModEffects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side sync for TeamGlow on Mohist/hybrid servers.
 * Sends custom packets so clients can render glow + nametag even if effects don't sync.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TeamGlowSyncHandler {

    private static final Set<UUID> LAST_GLOWING = new HashSet<>();
    private static final Set<UUID> CURRENT_GLOWING = new HashSet<>();

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ModEffects.TEAM_GLOW.isPresent()) return;
        if (!event.getEffectInstance().getEffect().equals(ModEffects.TEAM_GLOW.get())) return;

        ModNetworkHandler.INSTANCE.send(
                PacketDistributor.ALL.noArg(),
                new TeamGlowSyncPacket(player.getUUID(), true)
        );
        LAST_GLOWING.add(player.getUUID());
    }

    @SubscribeEvent
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ModEffects.TEAM_GLOW.isPresent()) return;
        if (!event.getEffect().equals(ModEffects.TEAM_GLOW.get())) return;

        ModNetworkHandler.INSTANCE.send(
                PacketDistributor.ALL.noArg(),
                new TeamGlowSyncPacket(player.getUUID(), false)
        );
        LAST_GLOWING.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onEffectExpired(MobEffectEvent.Expired event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ModEffects.TEAM_GLOW.isPresent()) return;
        var effectInstance = event.getEffectInstance();
        if (effectInstance == null) return;
        if (!effectInstance.getEffect().equals(ModEffects.TEAM_GLOW.get())) return;

        ModNetworkHandler.INSTANCE.send(
                PacketDistributor.ALL.noArg(),
                new TeamGlowSyncPacket(player.getUUID(), false)
        );
        LAST_GLOWING.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer viewer)) return;
        if (!ModEffects.TEAM_GLOW.isPresent()) return;

        for (ServerPlayer target : viewer.server.getPlayerList().getPlayers()) {
            if (target.hasEffect(ModEffects.TEAM_GLOW.get())) {
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> viewer),
                        new TeamGlowSyncPacket(target.getUUID(), true)
                );
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ModEffects.TEAM_GLOW.isPresent()) return;

        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        CURRENT_GLOWING.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasEffect(ModEffects.TEAM_GLOW.get())) {
                CURRENT_GLOWING.add(player.getUUID());
                if (!LAST_GLOWING.contains(player.getUUID())) {
                    ModNetworkHandler.INSTANCE.send(
                            PacketDistributor.ALL.noArg(),
                            new TeamGlowSyncPacket(player.getUUID(), true)
                    );
                }
            }
        }

        for (UUID prev : LAST_GLOWING) {
            if (!CURRENT_GLOWING.contains(prev)) {
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.ALL.noArg(),
                        new TeamGlowSyncPacket(prev, false)
                );
            }
        }

        LAST_GLOWING.clear();
        LAST_GLOWING.addAll(CURRENT_GLOWING);
    }
}
