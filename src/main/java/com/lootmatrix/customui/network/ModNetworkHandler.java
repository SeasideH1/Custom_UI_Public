package com.lootmatrix.customui.network;

import com.lootmatrix.customui.Main;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Network handler for client-server communication.
 */
public class ModNetworkHandler {

    private static final String PROTOCOL_VERSION = "5";

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Main.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    private static int nextId() {
        return packetId++;
    }

    /**
     * Register all network packets.
     * Called during mod initialization.
     */
    public static void register() {
        // Register damage number packet (server -> client)
        INSTANCE.messageBuilder(DamageNumberPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(DamageNumberPacket::new)
                .encoder(DamageNumberPacket::encode)
                .consumerMainThread(DamageNumberPacket::handle)
                .add();

        // Register damage indicator packet (server -> client)
        INSTANCE.messageBuilder(DamageIndicatorPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(DamageIndicatorPacket::new)
                .encoder(DamageIndicatorPacket::encode)
                .consumerMainThread(DamageIndicatorPacket::handle)
                .add();

        // Register scoreboard overlay config packet (server -> client)
        INSTANCE.messageBuilder(ScoreboardOverlayConfigPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(ScoreboardOverlayConfigPacket::new)
                .encoder(ScoreboardOverlayConfigPacket::encode)
                .consumerMainThread(ScoreboardOverlayConfigPacket::handle)
                .add();

        // Register scoreboard overlay update packet (server -> client, delta-compressed)
        INSTANCE.messageBuilder(ScoreboardOverlayUpdatePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(ScoreboardOverlayUpdatePacket::new)
                .encoder(ScoreboardOverlayUpdatePacket::encode)
                .consumerMainThread(ScoreboardOverlayUpdatePacket::handle)
                .add();

        // Register title packet (server -> client)
        INSTANCE.messageBuilder(TitlePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(TitlePacket::new)
                .encoder(TitlePacket::encode)
                .consumerMainThread(TitlePacket::handle)
                .add();

        // Register title image packet (server -> client)
        INSTANCE.messageBuilder(TitleImagePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(TitleImagePacket::new)
                .encoder(TitleImagePacket::encode)
                .consumerMainThread(TitleImagePacket::handle)
                .add();


        // Register kill message packet (server -> client)
        INSTANCE.messageBuilder(KillMessagePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(KillMessagePacket::new)
                .encoder(KillMessagePacket::encode)
                .consumerMainThread(KillMessagePacket::handle)
                .add();

        // Register kill message toggle packet (server -> client)
        INSTANCE.messageBuilder(KillMessageTogglePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(KillMessageTogglePacket::new)
                .encoder(KillMessageTogglePacket::encode)
                .consumerMainThread(KillMessageTogglePacket::handle)
                .add();

        // Register kill message mode packet (server -> client)
        INSTANCE.messageBuilder(KillMessageModePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(KillMessageModePacket::new)
                .encoder(KillMessageModePacket::encode)
                .consumerMainThread(KillMessageModePacket::handle)
                .add();

        // Register team glow sync packet (server -> client)
        INSTANCE.messageBuilder(TeamGlowSyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(TeamGlowSyncPacket::new)
                .encoder(TeamGlowSyncPacket::encode)
                .consumerMainThread(TeamGlowSyncPacket::handle)
                .add();

        // Register cinematic camera packet (server -> client)
        INSTANCE.messageBuilder(CinematicCameraPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(CinematicCameraPacket::new)
                .encoder(CinematicCameraPacket::encode)
                .consumerMainThread(CinematicCameraPacket::handle)
                .add();

        // Register atmosphere packet (server -> client)
        INSTANCE.messageBuilder(AtmospherePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(AtmospherePacket::new)
                .encoder(AtmospherePacket::encode)
                .consumerMainThread(AtmospherePacket::handle)
                .add();

        // Register capture zone sync packet (server -> client, delta-compressed)
        INSTANCE.messageBuilder(CaptureZoneSyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(CaptureZoneSyncPacket::new)
                .encoder(CaptureZoneSyncPacket::encode)
                .consumerMainThread(CaptureZoneSyncPacket::handle)
                .add();

        // Register capture zone geometry sync packet (server -> client)
        INSTANCE.messageBuilder(CaptureZoneGeometrySyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(CaptureZoneGeometrySyncPacket::new)
                .encoder(CaptureZoneGeometrySyncPacket::encode)
                .consumerMainThread(CaptureZoneGeometrySyncPacket::handle)
                .add();

        // Register HUD template definition sync packet (server -> client, chunked)
        INSTANCE.messageBuilder(HudTemplateSyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(HudTemplateSyncPacket::new)
                .encoder(HudTemplateSyncPacket::encode)
                .consumerMainThread(HudTemplateSyncPacket::handle)
                .add();

        // Register HUD playback control packet (server -> client, lightweight)
        INSTANCE.messageBuilder(HudPlayPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(HudPlayPacket::new)
                .encoder(HudPlayPacket::encode)
                .consumerMainThread(HudPlayPacket::handle)
                .add();

        // Register HUD template editor upload packet (client -> server)
        INSTANCE.messageBuilder(HudTemplateUploadPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(HudTemplateUploadPacket::new)
                .encoder(HudTemplateUploadPacket::encode)
                .consumerMainThread(HudTemplateUploadPacket::handle)
                .add();

        // Register HUD scoreboard binding sync packet (server -> client, delta-compressed)
        INSTANCE.messageBuilder(HudScoreboardSyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(HudScoreboardSyncPacket::new)
                .encoder(HudScoreboardSyncPacket::encode)
                .consumerMainThread(HudScoreboardSyncPacket::handle)
                .add();

        // Register HUD entity field binding sync packet (server -> client, delta-compressed)
        INSTANCE.messageBuilder(HudEntitySyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(HudEntitySyncPacket::new)
                .encoder(HudEntitySyncPacket::encode)
                .consumerMainThread(HudEntitySyncPacket::handle)
                .add();

        // Register GUI template interaction packet (client -> server, server-validated)
        INSTANCE.messageBuilder(GuiInteractPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(GuiInteractPacket::new)
                .encoder(GuiInteractPacket::encode)
                .consumerMainThread(GuiInteractPacket::handle)
                .add();

        // Register network metrics probe (client -> server) and echo (server -> client)
        INSTANCE.messageBuilder(NetPingPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(NetPingPacket::new)
                .encoder(NetPingPacket::encode)
                .consumerMainThread(NetPingPacket::handle)
                .add();
        INSTANCE.messageBuilder(NetPongPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(NetPongPacket::new)
                .encoder(NetPongPacket::encode)
                .consumerMainThread(NetPongPacket::handle)
                .add();

        // Register HUD template editor delete packet (client -> server)
        INSTANCE.messageBuilder(HudTemplateDeletePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(HudTemplateDeletePacket::new)
                .encoder(HudTemplateDeletePacket::encode)
                .consumerMainThread(HudTemplateDeletePacket::handle)
                .add();

        // Register HUD template removal sync packet (server -> client, id only)
        INSTANCE.messageBuilder(HudTemplateRemovePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(HudTemplateRemovePacket::new)
                .encoder(HudTemplateRemovePacket::encode)
                .consumerMainThread(HudTemplateRemovePacket::handle)
                .add();
    }
}
