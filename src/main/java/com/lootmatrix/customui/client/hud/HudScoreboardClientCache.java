package com.lootmatrix.customui.client.hud;

import com.lootmatrix.customui.Main;
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
 * Client cache of scoreboard values bound by HUD templates.
 * The server pushes a binding table (DEFINE) and index-based deltas; the render
 * path only does one map lookup per bound element per frame.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HudScoreboardClientCache {

    private static final List<String> KEYS = new ArrayList<>();
    private static final Map<String, Integer> VALUES = new HashMap<>();

    private HudScoreboardClientCache() {}

    /** DEFINE packet: replace the binding table and all values. */
    public static void applyDefine(List<String> keys, int[] values) {
        KEYS.clear();
        VALUES.clear();
        for (int i = 0; i < keys.size(); i++) {
            KEYS.add(keys.get(i));
            VALUES.put(keys.get(i), values[i]);
        }
    }

    /** DELTA packet: update changed values by table index. */
    public static void applyDelta(int[] indices, int[] values) {
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (index >= 0 && index < KEYS.size()) {
                VALUES.put(KEYS.get(index), values[i]);
            }
        }
    }

    /** Current value for a binding key ("objective\u0000holder"); 0 when unknown. */
    public static int value(String key) {
        Integer v = VALUES.get(key);
        return v != null ? v : 0;
    }

    /** Whether the server-synced binding table currently carries this key. */
    public static boolean has(String key) {
        return VALUES.containsKey(key);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        KEYS.clear();
        VALUES.clear();
    }
}
