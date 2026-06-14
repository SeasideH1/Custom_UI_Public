package com.lootmatrix.customui;

import net.minecraft.core.BlockPos;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Loads client bootstrap code only when the client package is present.
 * This keeps common classes free from direct client-only type references.
 */
public final class ClientBootstrapBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientBootstrapBridge.class);
    private static final String CLIENT_BOOTSTRAP = "com.lootmatrix.customui.client.ClientBootstrap";

    private ClientBootstrapBridge() {}

    public static void registerModEventListeners(IEventBus modEventBus) {
        invoke("registerModEventListeners", new Class<?>[]{IEventBus.class}, modEventBus);
    }

    public static void registerClientConfigs(FMLJavaModLoadingContext context) {
        invoke("registerClientConfigs", new Class<?>[]{FMLJavaModLoadingContext.class}, context);
    }

    public static void onAddPackFinders(AddPackFindersEvent event) {
        invoke("onAddPackFinders", new Class<?>[]{AddPackFindersEvent.class}, event);
    }

    public static void onVoidBarrierAdded(BlockPos pos) {
        invoke("onVoidBarrierAdded", new Class<?>[]{BlockPos.class}, pos);
    }

    public static void onVoidBarrierRemoved(BlockPos pos) {
        invoke("onVoidBarrierRemoved", new Class<?>[]{BlockPos.class}, pos);
    }

    private static void invoke(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> type = Class.forName(CLIENT_BOOTSTRAP);
            Method method = type.getMethod(methodName, parameterTypes);
            method.invoke(null, args);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("[CustomUI] Optional client class not present in this jar: {}", CLIENT_BOOTSTRAP);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke client bootstrap method " + methodName, e);
        }
    }
}
