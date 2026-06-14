package com.lootmatrix.customui.client.hud;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.hud.HudTemplate;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side playback state for active HUD template instances.
 * The server only sends play/seek/stop commands; time advances locally on the
 * client tick so playback stays smooth even with network jitter.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HudPlaybackManager {

    /** Mutable, pooled playback instance. */
    public static final class HudInstance {
        public HudTemplate template;
        public String templateId = "";
        public float elapsedTicks;
        public boolean active;
        /** Lifetime resolved once at play/replace time so ticking never rescans elements. */
        int lifetimeTicks;
        boolean persistent;

        void reset(HudTemplate template, float elapsed) {
            this.template = template;
            this.templateId = template.id;
            this.elapsedTicks = elapsed;
            this.active = true;
            this.persistent = template.isPersistent();
            this.lifetimeTicks = this.persistent ? 0 : template.effectiveLifetime();
        }
    }

    private static final List<HudInstance> INSTANCES = new ArrayList<>();
    private static final List<HudInstance> POOL = new ArrayList<>();

    private HudPlaybackManager() {}

    // ==================== Control (called from packet bridge) ====================

    public static void play(String templateId, int elapsedTicks) {
        HudTemplate template = HudClientTemplateCache.get(templateId);
        if (template == null) return;
        if (template.isGui()) {
            // GUI templates rendered through the passive overlay would leave the
            // mouse grabbed (camera keeps turning); always open them as a screen.
            GuiTemplateScreen.open(template.id, true);
            return;
        }
        HudInstance existing = find(template.id);
        if (existing != null) {
            existing.reset(template, elapsedTicks);
            return;
        }
        HudInstance instance = POOL.isEmpty() ? new HudInstance() : POOL.remove(POOL.size() - 1);
        instance.reset(template, elapsedTicks);
        INSTANCES.add(instance);
    }

    public static void seek(String templateId, int elapsedTicks) {
        HudInstance instance = find(templateId);
        if (instance != null) {
            instance.elapsedTicks = elapsedTicks;
        } else {
            // Seeking a non-active template implicitly starts it at that stage
            play(templateId, elapsedTicks);
        }
    }

    public static void stop(String templateId) {
        for (int i = INSTANCES.size() - 1; i >= 0; i--) {
            if (INSTANCES.get(i).templateId.equals(templateId)) {
                recycle(i);
            }
        }
    }

    public static void stopAll() {
        for (int i = INSTANCES.size() - 1; i >= 0; i--) {
            recycle(i);
        }
    }

    private static HudInstance find(String templateId) {
        for (int i = 0; i < INSTANCES.size(); i++) {
            HudInstance instance = INSTANCES.get(i);
            if (instance.templateId.equals(templateId)) return instance;
        }
        return null;
    }

    private static void recycle(int index) {
        HudInstance instance = INSTANCES.remove(index);
        instance.active = false;
        instance.template = null;
        POOL.add(instance);
    }

    /** Render-thread read access; do not mutate. */
    public static List<HudInstance> instances() {
        return INSTANCES;
    }

    /** Live-edit support: re-point active instances at a freshly synced definition. */
    public static void onTemplateReplaced(String templateId, HudTemplate newTemplate) {
        for (int i = 0; i < INSTANCES.size(); i++) {
            HudInstance instance = INSTANCES.get(i);
            if (instance.templateId.equals(templateId)) {
                instance.template = newTemplate;
                instance.persistent = newTemplate.isPersistent();
                instance.lifetimeTicks = instance.persistent ? 0 : newTemplate.effectiveLifetime();
            }
        }
    }

    // ==================== Tick ====================

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || INSTANCES.isEmpty()) return;
        if (Minecraft.getInstance().isPaused()) return;
        for (int i = INSTANCES.size() - 1; i >= 0; i--) {
            HudInstance instance = INSTANCES.get(i);
            instance.elapsedTicks += 1f;
            HudTemplate template = instance.template;
            if (template == null) {
                recycle(i);
                continue;
            }
            if (instance.persistent) continue;
            if (instance.elapsedTicks >= instance.lifetimeTicks) {
                if (template.loop) {
                    instance.elapsedTicks -= instance.lifetimeTicks;
                } else {
                    recycle(i);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stopAll();
        HudClientTemplateCache.clear();
    }
}
