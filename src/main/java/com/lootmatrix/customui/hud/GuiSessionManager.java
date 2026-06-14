package com.lootmatrix.customui.hud;

import com.lootmatrix.customui.Main;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side GUI template sessions and click validation.
 *
 * Tracks which GUI each player currently has open, runs the template
 * open/close datapack functions, and — the anti-cheat core — re-evaluates an
 * element's scoreboard condition against the real scoreboard before executing
 * its onClick/onClickFail function.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GuiSessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuiSessionManager.class);
    /** Minimum ticks between accepted clicks per player (basic spam guard). */
    private static final long CLICK_INTERVAL_TICKS = 2;

    private static final Map<UUID, String> OPEN_GUIS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_CLICK_TICK = new ConcurrentHashMap<>();
    /** GUIs each player may open with their interact keys (set via /customui gui activate). */
    private static final Map<UUID, java.util.Set<String>> ACTIVATED = new ConcurrentHashMap<>();

    private GuiSessionManager() {}

    /** The GUI template id a player currently has open, or null. */
    @Nullable
    public static String openGui(ServerPlayer player) {
        return OPEN_GUIS.get(player.getUUID());
    }

    // ==================== Activation ====================

    public static void activate(ServerPlayer player, String templateId) {
        ACTIVATED.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet()).add(templateId);
    }

    public static void deactivate(ServerPlayer player, String templateId) {
        java.util.Set<String> set = ACTIVATED.get(player.getUUID());
        if (set != null) set.remove(templateId);
    }

    public static boolean isActivated(ServerPlayer player, String templateId) {
        java.util.Set<String> set = ACTIVATED.get(player.getUUID());
        return set != null && set.contains(templateId);
    }

    /**
     * Player-initiated open (interact key). Requires prior activation; a stale
     * client that opened anyway gets a GUI_CLOSE pushed back.
     */
    public static boolean onOpen(ServerPlayer player, String templateId) {
        HudTemplate template = HudTemplateRegistry.getInstance().get(templateId);
        if (template == null || !template.isGui()) return false;
        if (!isActivated(player, template.id)) return false;
        forceOpen(player, template);
        return true;
    }

    /** Command/server-driven open: bypasses the activation gate. */
    public static void forceOpen(ServerPlayer player, HudTemplate template) {
        OPEN_GUIS.put(player.getUUID(), template.id);
        if (template.openKey != null && template.openKey.function != null) {
            runFunction(player, template.openKey.function);
        }
    }

    public static void onClose(ServerPlayer player, String templateId) {
        String current = OPEN_GUIS.remove(player.getUUID());
        HudTemplate template = HudTemplateRegistry.getInstance().get(templateId);
        if (template == null) return;
        // Accept the close even if our book-keeping disagrees, but log it
        if (current != null && !current.equals(template.id)) {
            LOGGER.debug("[CustomUI] GUI close mismatch for {}: tracked={}, reported={}",
                    player.getScoreboardName(), current, template.id);
        }
        if (template.closeKey != null && template.closeKey.function != null) {
            runFunction(player, template.closeKey.function);
        }
    }

    public static void onClick(ServerPlayer player, String templateId, String elementId) {
        MinecraftServer server = player.getServer();
        if (server == null || elementId.isEmpty()) return;
        HudTemplate template = HudTemplateRegistry.getInstance().get(templateId);
        if (template == null || !template.isGui()) return;

        long now = server.overworld().getGameTime();
        Long last = LAST_CLICK_TICK.put(player.getUUID(), now);
        if (last != null && now - last < CLICK_INTERVAL_TICKS) return;

        HudElement element = findById(template.elements, elementId, 0);
        if (element == null) return;

        // Authoritative condition check against the real scoreboard
        HudCondition condition = HudCondition.parse(element.condition);
        boolean pass = condition == null || condition.evaluateServer(server.getScoreboard(), player);
        HudInteraction interaction = pass ? element.onClick : element.onClickFail;
        if (interaction != null && interaction.function != null) {
            runFunction(player, interaction.function);
        }
    }

    @Nullable
    private static HudElement findById(List<HudElement> list, String id, int depth) {
        if (depth > HudTemplate.MAX_NESTING_DEPTH) return null;
        for (HudElement e : list) {
            if (id.equals(e.id)) return e;
            if (!e.children.isEmpty()) {
                HudElement found = findById(e.children, id, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Run a datapack function as the player (suppressed output, permission 2). */
    public static void runFunction(ServerPlayer player, String functionId) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ResourceLocation rl = ResourceLocation.tryParse(functionId);
        if (rl == null) {
            LOGGER.warn("[CustomUI] Invalid GUI function id: {}", functionId);
            return;
        }
        server.getFunctions().get(rl).ifPresentOrElse(fn -> {
            CommandSourceStack source = player.createCommandSourceStack()
                    .withSuppressedOutput().withPermission(2);
            server.getFunctions().execute(fn, source);
        }, () -> LOGGER.warn("[CustomUI] GUI function not found: {}", functionId));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            OPEN_GUIS.remove(event.getEntity().getUUID());
            LAST_CLICK_TICK.remove(event.getEntity().getUUID());
            ACTIVATED.remove(event.getEntity().getUUID());
        }
    }
}
