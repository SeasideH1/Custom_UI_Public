package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.client.render.RenderResourceCache;
import com.lootmatrix.customui.config.CrosshairConfig;
import com.lootmatrix.customui.config.CursorEffectConfig;
import com.lootmatrix.customui.config.DamageNumberConfig;
import com.lootmatrix.customui.config.HotbarConfig;
import com.lootmatrix.customui.config.KillIconConfig;
import com.lootmatrix.customui.config.PerformanceConfig;
import com.lootmatrix.customui.config.ScoreboardOverlayConfig;
import com.lootmatrix.customui.config.TeamIndicatorConfig;
import com.lootmatrix.customui.config.WindowIconConfig;
import com.lootmatrix.customui.entity.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.ArrayList;
import java.util.List;

public final class ClientBootstrap {

    private static final int TEXTURE_PREWARM_BUDGET_PER_TICK = 4;

    private ClientBootstrap() {}

    public static void registerModEventListeners(IEventBus modEventBus) {
        modEventBus.addListener(ClientBootstrap::onClientSetup);
        modEventBus.addListener(ClientBootstrap::registerRenderers);
        modEventBus.addListener(com.lootmatrix.customui.client.hud.HudKeyBindings::onRegisterKeyMappings);
        // Forge bus: raw key presses open GUI templates bound to an open key
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
                .register(com.lootmatrix.customui.client.hud.HudKeyBindings.class);
    }

    public static void registerClientConfigs(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, DamageNumberConfig.SPEC, Main.MODID + "-damage-numbers.toml");
        context.registerConfig(ModConfig.Type.CLIENT, HotbarConfig.SPEC, Main.MODID + "-adventure-hotbar.toml");
        context.registerConfig(ModConfig.Type.CLIENT, KillIconConfig.SPEC, Main.MODID + "-kill-icon.toml");
        context.registerConfig(ModConfig.Type.CLIENT, ScoreboardOverlayConfig.SPEC, Main.MODID + "-scoreboard-overlay.toml");
        context.registerConfig(ModConfig.Type.CLIENT, CursorEffectConfig.SPEC, Main.MODID + "-cursor-effects.toml");
        context.registerConfig(ModConfig.Type.CLIENT, TeamIndicatorConfig.SPEC, Main.MODID + "-team-indicator.toml");
        context.registerConfig(ModConfig.Type.CLIENT, WindowIconConfig.SPEC, Main.MODID + "-window-icon.toml");
        context.registerConfig(ModConfig.Type.CLIENT, CrosshairConfig.SPEC, Main.MODID + "-crosshair.toml");
        context.registerConfig(ModConfig.Type.CLIENT, PerformanceConfig.SPEC, Main.MODID + "-performance.toml");
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        com.lootmatrix.customui.StartupProgress.log("Client setup: HUD renderers + window + packs");
        TaczKillEventHandler.init();
        ScoreboardIconPresets.getInstance().init();
        event.enqueueWork(WindowIconHandler::init);
        event.enqueueWork(ClientBootstrap::scheduleUiTexturePrewarm);
        event.enqueueWork(ClientResourcePackReloader::applyMemorysCityDefaultSelectionOnce);
        event.enqueueWork(ClientBootstrap::forceDarkLoadingBackground);
        CaptureZoneHudRenderer.register();
        CaptureZoneBoundaryRenderer.register();
        com.lootmatrix.customui.StartupProgress.log("Client setup complete");
    }

    /**
     * The FML early window runs before any mod code and is red/orange by default,
     * so the first seconds of startup clash with the dark CustomUI loading theme.
     * It honors the vanilla {@code darkMojangStudiosBackground} accessibility
     * option (read from options.txt at boot); force it on so every subsequent
     * launch is dark-themed from the very first frame the window appears.
     */
    private static void forceDarkLoadingBackground() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.options != null && !mc.options.darkMojangStudiosBackground().get()) {
            mc.options.darkMojangStudiosBackground().set(true);
            mc.options.save();
            com.lootmatrix.customui.StartupProgress.log("Dark loading background enabled for next launches");
        }
    }

    static void scheduleUiTexturePrewarm() {
        List<net.minecraft.resources.ResourceLocation> resources = new ArrayList<>();
        resources.add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/icons/kill.png"));
        resources.add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/icons/kill_headshot.png"));
        resources.add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/overlay/generic.png"));
        resources.add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/overlay/headshot.png"));
        resources.add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/overlay/melee.png"));
        resources.add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/cursor/trail_glow.png"));
        resources.add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/cursor/soft_particle.png"));
        resources.add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/cursor/cursor_dot.png"));
        resources.addAll(CustomHealthOverlay.getCustomIconTextures());
        RenderResourceCache.scheduleTexturePrewarm(resources);
    }

    private static void registerRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.DEATH_MARKER.get(), DeathMarkerEntityRenderer::new);
    }

    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }

        event.addRepositorySource(consumer -> {
            // AiMatrix remains required. Memorys City is optional and selected once by the reloader.
            Pack aimatrix = createBundledPack(
                    ClientResourcePackReloader.REQUIRED_PACK_ID,
                    Main.REQUIRED_PACK_ARCHIVE,
                    "CustomUI Required Resources",
                    true,
                    true);
            if (aimatrix != null) {
                consumer.accept(aimatrix);
            }

            Pack memorysCity = createBundledPack(
                    ClientResourcePackReloader.MEMORYS_CITY_PACK_ID,
                    Main.MEMORYS_CITY_PACK_ARCHIVE,
                    "Memorys City Resources",
                    false,
                    false);
            if (memorysCity != null) {
                consumer.accept(memorysCity);
            }
        });
    }

    private static Pack createBundledPack(String packId, String archivePath, String title,
                                          boolean required, boolean fixedPosition) {
        Pack.ResourcesSupplier resourcesSupplier = id -> new BundledZipResourcePack(id, archivePath);
        Pack.Info packInfo = Pack.readPackInfo(packId, resourcesSupplier);
        return packInfo == null ? null : Pack.create(
                packId,
                Component.literal(title),
                required,
                resourcesSupplier,
                packInfo,
                PackType.CLIENT_RESOURCES,
                Pack.Position.TOP,
                fixedPosition,
                PackSource.BUILT_IN
        );
    }

    public static void onVoidBarrierAdded(BlockPos pos) {
        com.lootmatrix.customui.client.render.VoidBarrierWorldRenderer.onBlockAdded(pos.immutable());
    }

    public static void onVoidBarrierRemoved(BlockPos pos) {
        com.lootmatrix.customui.client.render.VoidBarrierWorldRenderer.onBlockRemoved(pos);
    }

    @Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientResourcePackEvents {
        private ClientResourcePackEvents() {}

        @SubscribeEvent
        public static void onAddPackFindersEvent(AddPackFindersEvent event) {
            ClientBootstrap.onAddPackFinders(event);
        }
    }

    /**
     * Texture prewarm tick handler — must be on the FORGE bus because
     * {@link TickEvent.ClientTickEvent} is a FORGE-bus event.
     * Previously this was inside {@code ClientResourcePackEvents} which
     * subscribes to the MOD bus, so the handler never fired and all
     * queued textures were loaded synchronously on the first render frame.
     */
    @Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ClientTexturePrewarmTick {
        private ClientTexturePrewarmTick() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            RenderResourceCache.prewarmScheduledTextures(TEXTURE_PREWARM_BUDGET_PER_TICK);
        }
    }
}
