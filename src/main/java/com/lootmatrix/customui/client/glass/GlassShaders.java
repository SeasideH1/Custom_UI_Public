package com.lootmatrix.customui.client.glass;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.client.title.TitleSceneManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Core shader registration for the frosted-glass widget pipeline plus the
 * client reload hook that invalidates the baked title scene (atlas UVs move
 * across resource reloads).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GlassShaders {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlassShaders.class);

    @Nullable private static ShaderInstance blurShader;
    @Nullable private static ShaderInstance panelShader;

    private GlassShaders() {}

    @Nullable
    public static ShaderInstance blur() {
        return blurShader;
    }

    @Nullable
    public static ShaderInstance panel() {
        return panelShader;
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Main.MODID, "glass_blur"),
                    DefaultVertexFormat.POSITION_TEX), shader -> blurShader = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Main.MODID, "glass_panel"),
                    DefaultVertexFormat.POSITION), shader -> panelShader = shader);
        } catch (IOException exception) {
            LOGGER.error("[CustomUI] Failed to register glass shaders, frosted buttons disabled", exception);
        }
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener)
                resourceManager -> TitleSceneManager.invalidate());
    }
}
