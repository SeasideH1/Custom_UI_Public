package com.lootmatrix.customui.client.hud;

import com.lootmatrix.customui.hud.HudGuiKey;
import com.lootmatrix.customui.hud.HudTemplate;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Three generic, player-rebindable GUI interact keys (vanilla Controls →
 * CustomUI category, unbound by default). GUI templates pick a slot via
 * {@code gui.open.interactKey} (1-3); slot 0 / unbound slots fall back to
 * the template's declared raw default key (e.g. "key.keyboard.b").
 *
 * Keybind text components ({"keybind": "key.customui.interact1"}) resolve
 * to the player's actual binding automatically through vanilla rendering.
 */
@OnlyIn(Dist.CLIENT)
public final class HudKeyBindings {

    public static final String CATEGORY = "key.categories.customui";

    public static final KeyMapping INTERACT_1 = new KeyMapping(
            "key.customui.interact1", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);
    public static final KeyMapping INTERACT_2 = new KeyMapping(
            "key.customui.interact2", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);
    public static final KeyMapping INTERACT_3 = new KeyMapping(
            "key.customui.interact3", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);

    /** GUIs the server has activated for this player (interact-key opening gate). */
    private static final java.util.Set<String> ACTIVATED_GUIS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private HudKeyBindings() {}

    /** GUI_ACTIVATE / GUI_DEACTIVATE packets. */
    public static void setActivated(String templateId, boolean activated) {
        if (activated) {
            ACTIVATED_GUIS.add(templateId);
        } else {
            ACTIVATED_GUIS.remove(templateId);
        }
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(INTERACT_1);
        event.register(INTERACT_2);
        event.register(INTERACT_3);
    }

    @Nullable
    public static KeyMapping interactMapping(int slot) {
        return switch (slot) {
            case 1 -> INTERACT_1;
            case 2 -> INTERACT_2;
            case 3 -> INTERACT_3;
            default -> null;
        };
    }

    /**
     * Does a pressed (key, scanCode) trigger this template key declaration?
     * Bound interact slots take priority; slot 0 or an unbound slot falls
     * back to the template's raw default key name.
     */
    public static boolean matches(@Nullable HudGuiKey guiKey, int keyCode, int scanCode) {
        if (guiKey == null) return false;
        KeyMapping mapping = interactMapping(guiKey.interactKey);
        if (mapping != null && !mapping.isUnbound()) {
            return mapping.matches(keyCode, scanCode);
        }
        if (guiKey.key.isEmpty()) return false;
        try {
            InputConstants.Key key = InputConstants.getKey(guiKey.key);
            return key.getValue() != InputConstants.UNKNOWN.getValue()
                    && key.getValue() == keyCode;
        } catch (Exception e) {
            return false;
        }
    }

    /** Raw key press hook: opens GUI templates bound to an open key. */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (ACTIVATED_GUIS.isEmpty()) return;
        for (Map.Entry<String, HudTemplate> entry : HudClientTemplateCache.snapshot().entrySet()) {
            HudTemplate template = entry.getValue();
            if (!template.isGui() || template.openKey == null || !template.openKey.isUsable()) continue;
            // Only server-activated GUIs respond to interact keys
            if (!ACTIVATED_GUIS.contains(template.id)) continue;
            if (matches(template.openKey, event.getKey(), event.getScanCode())) {
                GuiTemplateScreen.open(template.id, true);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ACTIVATED_GUIS.clear();
    }
}
