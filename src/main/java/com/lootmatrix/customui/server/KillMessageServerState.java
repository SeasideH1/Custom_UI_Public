package com.lootmatrix.customui.server;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.KillMessageModePacket;
import com.lootmatrix.customui.network.KillMessageTogglePacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side state manager for KillMessage settings.
 *
 * Stores the global enabled/mode state and syncs to new players on login.
 * This ensures players joining after configuration was set still receive the correct settings.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class KillMessageServerState {

    private static final Logger LOGGER = LoggerFactory.getLogger(KillMessageServerState.class);

    /** Global kill message enabled state (default: true) */
    private static boolean enabled = true;

    /** Global kill message display mode (default: "AllyTeam") */
    private static String mode = "AllyTeam";

    /**
     * Get the current enabled state.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Set the enabled state and optionally broadcast to all players.
     */
    public static void setEnabled(boolean newEnabled, boolean broadcast) {
        enabled = newEnabled;

        if (broadcast) {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    ModNetworkHandler.INSTANCE.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new KillMessageTogglePacket(enabled)
                    );
                }
            }
        }
    }

    /**
     * Get the current display mode.
     */
    public static String getMode() {
        return mode;
    }

    /**
     * Set the display mode and optionally broadcast to all players.
     */
    public static void setMode(String newMode, boolean broadcast) {
        mode = newMode;

        if (broadcast) {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    ModNetworkHandler.INSTANCE.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new KillMessageModePacket(mode)
                    );
                }
            }
        }
    }

    /**
     * Sync current state to a newly joined player.
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;


        // Send current enabled state
        ModNetworkHandler.INSTANCE.send(
                PacketDistributor.PLAYER.with(() -> player),
                new KillMessageTogglePacket(enabled)
        );

        // Send current mode
        ModNetworkHandler.INSTANCE.send(
                PacketDistributor.PLAYER.with(() -> player),
                new KillMessageModePacket(mode)
        );
    }
}

