package com.lootmatrix.customui.capturezone;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Collection;

/**
 * Server commands for the capture zone system.
 * /customui zone list            - List all loaded zone definitions
 * /customui zone activate <id>   - Start tracking a zone
 * /customui zone deactivate <id> - Stop tracking a zone
 * /customui zone reset <id>      - Reset a zone's progress
 * /customui zone status <id>     - Show zone status
 * /customui zone resetall        - Reset all zones
 */
public class CaptureZoneCommand {

    private static final SuggestionProvider<CommandSourceStack> ZONE_ID_SUGGESTIONS = (ctx, builder) -> {
        Collection<CaptureZone> zones = CaptureZoneManager.getInstance().getAllZones();
        return SharedSuggestionProvider.suggest(zones.stream().map(z -> z.id), builder);
    };

    private static final SuggestionProvider<CommandSourceStack> ACTIVE_ZONE_SUGGESTIONS = (ctx, builder) -> {
        return SharedSuggestionProvider.suggest(CaptureZoneManager.getInstance().getActiveZoneIds(), builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("customui")
                .then(Commands.literal("zone")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("list").executes(CaptureZoneCommand::listZones))
                        .then(Commands.literal("activate")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .suggests(ZONE_ID_SUGGESTIONS)
                                        .executes(CaptureZoneCommand::activateZone)))
                        .then(Commands.literal("deactivate")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .suggests(ACTIVE_ZONE_SUGGESTIONS)
                                        .executes(CaptureZoneCommand::deactivateZone)))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .suggests(ACTIVE_ZONE_SUGGESTIONS)
                                        .executes(CaptureZoneCommand::resetZone)))
                        .then(Commands.literal("status")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .suggests(ACTIVE_ZONE_SUGGESTIONS)
                                        .executes(CaptureZoneCommand::statusZone)))
                        .then(Commands.literal("resetall").executes(CaptureZoneCommand::resetAll))
                )
        );
    }

    private static int listZones(CommandContext<CommandSourceStack> ctx) {
        Collection<CaptureZone> zones = CaptureZoneManager.getInstance().getAllZones();
        if (zones.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("\u00a7e[CustomUI] No capture zones loaded."), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("\u00a7e[CustomUI] Loaded capture zones:"), false);
        for (CaptureZone zone : zones) {
            boolean active = CaptureZoneManager.getInstance().isActive(zone.id);
            String status = active ? "\u00a7a[ACTIVE]" : "\u00a77[INACTIVE]";
            ctx.getSource().sendSuccess(() ->
                    Component.literal("  " + status + " \u00a7f" + zone.id + " \u00a77(" + zone.displayName + ") " +
                            zone.ops.size() + " ops"), false);
        }
        return zones.size();
    }

    private static int activateZone(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        CaptureZone zone = CaptureZoneManager.getInstance().getZone(id);
        if (zone == null) {
            ctx.getSource().sendFailure(Component.literal("\u00a7c[CustomUI] Zone not found: " + id));
            return 0;
        }
        CaptureZoneManager.getInstance().activateZone(id);
        ctx.getSource().sendSuccess(() ->
                Component.literal("\u00a7a[CustomUI] Activated zone: " + id), true);
        // Save state
        if (ctx.getSource().getServer() != null) {
            CaptureZoneManager.getInstance().saveToWorld(ctx.getSource().getServer());
        }
        return 1;
    }

    private static int deactivateZone(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        CaptureZoneManager.getInstance().deactivateZone(id);
        ctx.getSource().sendSuccess(() ->
                Component.literal("\u00a7e[CustomUI] Deactivated zone: " + id), true);
        if (ctx.getSource().getServer() != null) {
            CaptureZoneManager.getInstance().saveToWorld(ctx.getSource().getServer());
        }
        return 1;
    }

    private static int resetZone(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        CaptureZoneManager.getInstance().resetZone(id);
        ctx.getSource().sendSuccess(() ->
                Component.literal("\u00a7e[CustomUI] Reset zone: " + id), true);
        if (ctx.getSource().getServer() != null) {
            CaptureZoneManager.getInstance().saveToWorld(ctx.getSource().getServer());
        }
        return 1;
    }

    private static int statusZone(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        CaptureZoneManager.ZoneState state = CaptureZoneManager.getInstance().getState(id);
        if (state == null) {
            ctx.getSource().sendFailure(Component.literal("\u00a7c[CustomUI] Zone not active: " + id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "\u00a7e[CustomUI] Zone " + id + ":\n" +
                "  Progress: \u00a7f" + Math.round(state.progress * 100) + "%\n" +
                "  Capturing: \u00a7f" + (state.capturingTeam != null ? state.capturingTeam : "none") + "\n" +
                "  Owner: \u00a7f" + (state.ownerTeam != null ? state.ownerTeam : "none") + "\n" +
                "  Contested: \u00a7f" + state.contested + "\n" +
                "  Players inside: \u00a7f" + state.playersInside.size()
        ), false);
        return 1;
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) {
        CaptureZoneManager mgr = CaptureZoneManager.getInstance();
        for (String id : mgr.getActiveZoneIds()) {
            mgr.resetZone(id);
        }
        ctx.getSource().sendSuccess(() ->
                Component.literal("\u00a7e[CustomUI] All zones reset."), true);
        if (ctx.getSource().getServer() != null) {
            mgr.saveToWorld(ctx.getSource().getServer());
        }
        return 1;
    }
}
