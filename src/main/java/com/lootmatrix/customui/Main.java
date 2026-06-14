package com.lootmatrix.customui;

import com.lootmatrix.customui.block.ModBlockEntities;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.registry.ModBlocks;
import com.lootmatrix.customui.registry.ModEnchantedBooks;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Main.MODID)
public class Main
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "customui";
    public static final String REQUIRED_PACK_ARCHIVE = "[\u5fc5\u9700]AiMatrix \u5fc5\u9700\u8d44\u6e90\u5305.zip";
    public static final String MEMORYS_CITY_PACK_ARCHIVE = "[\u63a8\u8350]Memorys City \u65b9\u5757\u6750\u8d28\u5305.zip";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    public Main(FMLJavaModLoadingContext context)
    {
        StartupProgress.log("Constructing mod (registries + event listeners)");
        IEventBus modEventBus = context.getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegister);

        ClientBootstrapBridge.registerModEventListeners(modEventBus);

        // Register ALL blocks, items, and creative tabs (consolidated in ModBlocks)
        ModBlocks.register(modEventBus);

        // Register block entity types
        ModBlockEntities.register(modEventBus);

        // Register entity types
        com.lootmatrix.customui.entity.ModEntities.register(modEventBus);

        // Register mob effects (potion effects)
        com.lootmatrix.customui.registry.ModEffects.register(modEventBus);

        // Register enchantments
        com.lootmatrix.customui.registry.ModEnchantments.register(modEventBus);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register command event listener
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // Register datapack reload listener for cinematic camera paths
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        // context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Check config version and reset configs if mod version changed
        resetConfigsIfVersionChanged();

        // Hand the startup window to the themed loading overlay (next launch)
        migrateEarlyWindowConfig();

        ClientBootstrapBridge.registerClientConfigs(context);
        StartupProgress.log("Mod construction complete");
    }

    /**
     * Force-disable the FML early loading window so the game window only
     * appears once our themed loading overlay can drive it (no red/black
     * Forge splash). FML reads fml.toml before any mod code runs, so the
     * flipped value takes effect from the NEXT launch onward.
     */
    private static void migrateEarlyWindowConfig() {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            return;
        }
        try {
            if (!net.minecraftforge.fml.loading.FMLConfig.getBoolConfigValue(
                    net.minecraftforge.fml.loading.FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL)) {
                return; // already disabled
            }
            Path fmlToml = FMLPaths.FMLCONFIG.get();
            if (!Files.exists(fmlToml)) {
                return;
            }
            String content = Files.readString(fmlToml);
            String updated = content.replaceAll("(?m)^(\\s*earlyWindowControl\\s*=\\s*)true", "$1false");
            if (!updated.equals(content)) {
                Files.writeString(fmlToml, updated);
                LOGGER.info("[CustomUI] Disabled the FML early loading window (earlyWindowControl=false), effective from the next launch");
            }
        } catch (IOException e) {
            LOGGER.warn("[CustomUI] Failed to update fml.toml earlyWindowControl", e);
        }
    }

    /** Feeds the loading screen log with per-registry progress (own content only). */
    private void onRegister(net.minecraftforge.registries.RegisterEvent event) {
        var key = event.getRegistryKey();
        if (key.equals(net.minecraftforge.registries.ForgeRegistries.Keys.BLOCKS)
                || key.equals(net.minecraftforge.registries.ForgeRegistries.Keys.ITEMS)
                || key.equals(net.minecraftforge.registries.ForgeRegistries.Keys.BLOCK_ENTITY_TYPES)
                || key.equals(net.minecraftforge.registries.ForgeRegistries.Keys.ENTITY_TYPES)
                || key.equals(net.minecraftforge.registries.ForgeRegistries.Keys.MOB_EFFECTS)
                || key.equals(net.minecraftforge.registries.ForgeRegistries.Keys.ENCHANTMENTS)
                || key.equals(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB)) {
            StartupProgress.log("Registering " + key.location().getPath());
        }
    }

    /**
     * Check config version file and reset all config files if the mod version has changed.
     * This ensures users always get the latest default config values after updating the mod.
     */
    private void resetConfigsIfVersionChanged() {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path versionFile = configDir.resolve(MODID + "-config-version.txt");

            // Get current mod version from gradle.properties (injected at build time)
            String currentVersion = net.minecraftforge.fml.ModList.get()
                    .getModContainerById(MODID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");

            // Read stored config version
            String storedVersion = "";
            if (Files.exists(versionFile)) {
                storedVersion = Files.readString(versionFile).trim();
            }

            // If version changed, delete config files so they get recreated with new defaults
            if (!currentVersion.equals(storedVersion)) {
                LOGGER.info("[CustomUI] Mod version changed from '{}' to '{}', resetting config files to defaults",
                        storedVersion, currentVersion);

                String[] configFiles = {
                        MODID + "-damage-numbers.toml",
                        MODID + "-adventure-hotbar.toml",
                        MODID + "-kill-icon.toml",
                        MODID + "-crosshair.toml"
                };

                for (String configFile : configFiles) {
                    Path configPath = configDir.resolve(configFile);
                    if (Files.exists(configPath)) {
                        Files.delete(configPath);
                        LOGGER.info("[CustomUI] Deleted old config: {}", configFile);
                    }
                }

                // Write new version
                Files.writeString(versionFile, currentVersion);
                LOGGER.info("[CustomUI] Config version updated to: {}", currentVersion);
            }
        } catch (IOException e) {
            LOGGER.warn("[CustomUI] Failed to check/reset config version", e);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        StartupProgress.log("Common setup: networking + server bridges");
        // Register network packets
        event.enqueueWork(() -> {
            ModNetworkHandler.register();
            StartupProgress.log("Network channel ready");
        });

        // Force load server-side event handlers
        event.enqueueWork(() -> {
            OptionalServerBridge.registerCommonServerFeatures();

            // Register capture zone server-side manager
            com.lootmatrix.customui.capturezone.CaptureZoneManager.register();
            StartupProgress.log("Server-side features registered");
        });
    }

    /**
     * Register server commands.
     */
    private void onRegisterCommands(RegisterCommandsEvent event) {
        OptionalServerBridge.registerServerCommands(event.getDispatcher());
        com.lootmatrix.customui.cinematic.CinematicCameraCommand.register(event.getDispatcher());
        com.lootmatrix.customui.atmosphere.AtmosphereCommand.register(event.getDispatcher());
        com.lootmatrix.customui.capturezone.CaptureZoneCommand.register(event.getDispatcher());
        com.lootmatrix.customui.command.CustomUIDataCommand.register(event.getDispatcher());
        com.lootmatrix.customui.command.CanSeeCommand.register(event.getDispatcher());
        com.lootmatrix.customui.command.DistanceCommand.register(event.getDispatcher());
        com.lootmatrix.customui.hud.HudCommand.register(event.getDispatcher());
    }

    /**
     * Register datapack reload listeners.
     */
    private void onAddReloadListeners(net.minecraftforge.event.AddReloadListenerEvent event) {
        event.addListener(new com.lootmatrix.customui.cinematic.CameraPathLoader());
        event.addListener(new com.lootmatrix.customui.atmosphere.AtmospherePresetLoader());
        event.addListener(new com.lootmatrix.customui.capturezone.CaptureZoneLoader());
        event.addListener(new com.lootmatrix.customui.hud.HudTemplateLoader());
    }

    // Add items to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            // Safely add items with presence check
            try {
                // Add all vertical slab items
                for (RegistryObject<Item> slabItem : ModBlocks.VERTICAL_SLAB_ITEMS) {
                    if (slabItem.isPresent()) {
                        event.accept(slabItem);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[CustomUI] Error adding items to creative tab: {}", e.getMessage());
            }
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModEnchantedBooks.createSlownessImmunityBook());
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        StartupProgress.log("Loading world data (capture zones + HUD templates)");
        // Load capture zone saved state from world data
        com.lootmatrix.customui.capturezone.CaptureZoneManager.getInstance().loadFromWorld(event.getServer());

        // Load world-stored HUD templates (editor uploads)
        com.lootmatrix.customui.hud.HudTemplateRegistry.getInstance().loadFromWorld(event.getServer());
    }

}
