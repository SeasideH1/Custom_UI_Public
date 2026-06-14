package com.lootmatrix.customui.client.title;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.StartupProgress;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.LanguageSelectScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Lifecycle and event hub for the LabyMod-style 3D title background.
 *
 * Assets bake lazily: ideally triggered while the loading overlay starts its
 * fade-out (the one-off mesh bake hitch hides under the opaque cover), with a
 * fallback bake on the first title-screen frame. Mixins call into
 * {@link #renderTitleBackground} / {@link #renderMenuBackground}; both return
 * false so callers fall back to the vanilla panorama / dirt background when
 * the scene is disabled or its structure asset is missing.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class TitleSceneManager {

    @Nullable private static TitleSceneAssets.SceneConfig config;
    @Nullable private static SceneBlockGetter scene;
    @Nullable private static List<TitleSceneMesher.SceneLayer> layers;
    @Nullable private static TitleCameraDirector director;
    private static boolean configLoaded;
    private static boolean bakeAttempted;

    private TitleSceneManager() {}

    // ==================== Render entry points (mixins) ====================

    /** TitleScreen background; replaces the panorama when the scene is ready. */
    public static boolean renderTitleBackground(GuiGraphics graphics) {
        if (!ensureBaked()) {
            return false;
        }
        TitleSceneRenderer.render(graphics, layers, config, director.sample());
        return true;
    }

    /** Non-title menu background; replaces the dirt texture when scene is ready. */
    public static boolean renderMenuBackground(Screen screen, GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || !ensureBaked()) {
            return false;
        }
        TitleSceneRenderer.render(graphics, layers, config, director.sample());
        return true;
    }

    /** scene.json glassButtons flag (independent of the 3D scene asset). */
    public static boolean glassButtonsConfigured() {
        return ensureConfig().glassButtons;
    }

    /**
     * Called by the loading overlay the moment its fade-out starts: bake now
     * (hidden under the still-opaque cover) and start the entrance flight.
     */
    public static void onLoadingFadeOutStarted() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            return; // F3+T style reload inside a world: no entrance
        }
        if (ensureBaked()) {
            director.beginEntrance();
        }
    }

    /** Resource reload: meshes reference atlas UVs that may have moved. */
    public static void invalidate() {
        if (layers != null) {
            TitleSceneMesher.close(layers);
        }
        layers = null;
        scene = null;
        director = null;
        config = null;
        configLoaded = false;
        bakeAttempted = false;
        TitleSceneRenderer.closeLightmap();
    }

    // ==================== Screen-change choreography ====================

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (director == null || Minecraft.getInstance().level != null) {
            return;
        }
        String anchor = resolveAnchor(event.getNewScreen());
        if (anchor != null) {
            director.moveToAnchor(anchor);
        }
    }

    /** Maps menu screens to scene.json anchor keys; null = keep current shot. */
    @Nullable
    private static String resolveAnchor(@Nullable Screen screen) {
        if (screen == null) {
            return null;
        }
        if (screen instanceof TitleScreen) return "title";
        if (screen instanceof SelectWorldScreen || screen instanceof CreateWorldScreen) return "singleplayer";
        if (screen instanceof JoinMultiplayerScreen) return "multiplayer";
        if (screen instanceof RealmsMainScreen) return "realms";
        if (screen instanceof OptionsScreen) return "options";
        if (screen instanceof LanguageSelectScreen) return "language";
        if (screen instanceof PackSelectionScreen) return "packs";
        if (screen instanceof ConnectScreen
                || screen instanceof LevelLoadingScreen
                || screen instanceof ReceivingLevelScreen) {
            return "dive";
        }
        return null;
    }

    // ==================== Lazy loading ====================

    private static TitleSceneAssets.SceneConfig ensureConfig() {
        if (!configLoaded) {
            configLoaded = true;
            config = TitleSceneAssets.loadConfig(Minecraft.getInstance().getResourceManager());
        }
        return config;
    }

    private static boolean ensureBaked() {
        if (bakeAttempted) {
            return layers != null && !layers.isEmpty() && director != null;
        }
        bakeAttempted = true;
        TitleSceneAssets.SceneConfig loadedConfig = ensureConfig();
        if (!loadedConfig.sceneEnabled) {
            return false;
        }
        long start = Util.getMillis();
        scene = TitleSceneAssets.loadStructure(Minecraft.getInstance().getResourceManager(), loadedConfig);
        if (scene == null) {
            return false;
        }
        layers = TitleSceneMesher.bake(scene);
        if (layers.isEmpty()) {
            return false;
        }
        director = new TitleCameraDirector(loadedConfig);
        StartupProgress.log("Title scene baked in " + (Util.getMillis() - start)
                + " ms (" + layers.size() + " layers)");
        return true;
    }
}
