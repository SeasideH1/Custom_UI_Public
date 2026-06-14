package com.lootmatrix.customui.client.hud;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.hud.HudEntityBinding;
import com.lootmatrix.customui.hud.HudEntityValueResolver;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client cache of entity field values bound by HUD templates.
 * Falls back to local selector resolution when sync is unavailable (editor preview).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HudEntityClientCache {

    private static final List<String> KEYS = new ArrayList<>();
    private static final Map<String, String> VALUES = new HashMap<>();

    private HudEntityClientCache() {}

    public static void applyDefine(List<String> keys, List<String> values) {
        KEYS.clear();
        VALUES.clear();
        for (int i = 0; i < keys.size(); i++) {
            KEYS.add(keys.get(i));
            VALUES.put(keys.get(i), values.get(i));
        }
    }

    public static void applyDelta(int[] indices, List<String> values) {
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (index >= 0 && index < KEYS.size()) {
                VALUES.put(KEYS.get(index), values.get(i));
            }
        }
    }

    /** Current string value for a binding key; empty when unknown. */
    public static String value(String key) {
        String synced = VALUES.get(key);
        if (synced != null) return synced;
        return resolveLocal(key);
    }

    /** Numeric helper for PROGRESS bindings. */
    public static float numericValue(String key) {
        HudEntityBinding binding = HudEntityBinding.fromKey(key);
        if (binding == null || !binding.isNumericField()) return Float.NaN;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return Float.NaN;
        return HudEntityValueResolver.readNumeric(binding, mc.player.createCommandSourceStack());
    }

    /** Whether the binding currently resolves (synced or local preview). */
    public static boolean has(String key) {
        if (VALUES.containsKey(key)) return true;
        HudEntityBinding binding = HudEntityBinding.fromKey(key);
        if (binding == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return HudEntityValueResolver.resolveFirst(binding.selector, mc.player.createCommandSourceStack()) != null;
    }

    private static String resolveLocal(String key) {
        HudEntityBinding binding = HudEntityBinding.fromKey(key);
        if (binding == null) return "";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "";
        return HudEntityValueResolver.readValue(binding, mc.player.createCommandSourceStack());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        KEYS.clear();
        VALUES.clear();
    }
}
