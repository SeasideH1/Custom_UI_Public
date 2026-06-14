package com.lootmatrix.customui.atmosphere;

import com.lootmatrix.customui.Main;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side Forge event hooks for the atmosphere system.
 * Intercepts fog color/distance computation and ticking to drive the atmosphere engine.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class AtmosphereEventHook {

    /**
     * Tick the atmosphere engine each client tick.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        AtmosphereEngine engine = AtmosphereEngine.getInstance();
        if (engine.getActivePreset() != null || engine.getBlendFactor() > 0f) {
            engine.tick();
        }
    }

    /**
     * Override fog color when atmosphere is active.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        AtmosphereEngine engine = AtmosphereEngine.getInstance();
        AtmosphereEngine.CachedFogConfig fogConfig = engine.getCachedFogConfig();
        if (fogConfig == null) return;

        float pt = (float) event.getPartialTick();
        float blend = engine.getBlendFactor(pt);
        
        event.setRed(lerp(event.getRed(), fogConfig.r(), blend));
        event.setGreen(lerp(event.getGreen(), fogConfig.g(), blend));
        event.setBlue(lerp(event.getBlue(), fogConfig.b(), blend));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
