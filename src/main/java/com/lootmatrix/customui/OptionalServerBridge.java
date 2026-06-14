package com.lootmatrix.customui;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Loads server-package features only when those classes are present.
 * This keeps the distributed client jar free of server-only bytecode while
 * still allowing the full/dev jar and the server jar to register the same logic.
 */
public final class OptionalServerBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(OptionalServerBridge.class);

    private static final String PLAYER_DAMAGE_INDICATOR_HANDLER =
            "com.lootmatrix.customui.server.PlayerDamageIndicatorHandler";
    private static final String DAMAGE_EVENT_HANDLER =
            "com.lootmatrix.customui.server.DamageEventHandler";
    private static final String SCOREBOARD_OVERLAY_TICK_HANDLER =
            "com.lootmatrix.customui.server.ScoreboardOverlayTickHandler";
    private static final String SCOREBOARD_OVERLAY_COMMAND =
            "com.lootmatrix.customui.server.ScoreboardOverlayCommand";
    private static final String DEATH_MARKER_COMMAND =
            "com.lootmatrix.customui.server.DeathMarkerCommand";
    private static final String OBJECTIVE_COMMAND =
            "com.lootmatrix.customui.server.ObjectiveCommand";
    private static final String KILL_MESSAGE_COMMAND =
            "com.lootmatrix.customui.server.KillMessageCommand";

    private OptionalServerBridge() {}

    public static void registerCommonServerFeatures() {
        invokeNoArgs(PLAYER_DAMAGE_INDICATOR_HANDLER, "register");
        invokeNoArgs(DAMAGE_EVENT_HANDLER, "register");
        invokeNoArgs(DAMAGE_EVENT_HANDLER, "initTaczTracking");
        invokeNoArgs(SCOREBOARD_OVERLAY_TICK_HANDLER, "register");
    }

    public static void registerServerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        invokeDispatcher(KILL_MESSAGE_COMMAND, dispatcher);
        invokeDispatcher(SCOREBOARD_OVERLAY_COMMAND, dispatcher);
        invokeDispatcher(DEATH_MARKER_COMMAND, dispatcher);
        invokeDispatcher(OBJECTIVE_COMMAND, dispatcher);
    }

    private static void invokeDispatcher(String className, CommandDispatcher<CommandSourceStack> dispatcher) {
        try {
            Class<?> type = Class.forName(className);
            Method method = type.getMethod("register", CommandDispatcher.class);
            method.invoke(null, dispatcher);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("[CustomUI] Optional server class not present in this jar: {}", className);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke register(CommandDispatcher) on " + className, e);
        }
    }

    private static void invokeNoArgs(String className, String methodName) {
        try {
            Class<?> type = Class.forName(className);
            Method method = type.getMethod(methodName);
            method.invoke(null);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("[CustomUI] Optional server class not present in this jar: {}", className);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke " + className + "#" + methodName, e);
        }
    }
}
