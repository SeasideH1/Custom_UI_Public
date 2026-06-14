package com.lootmatrix.customui.server;

import com.lootmatrix.customui.config.ScoreboardOverlayConfig;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.network.ScoreboardOverlayConfigPacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.Collection;

/**
 * Server commands for controlling the scoreboard overlay.
 * Command tree: /customui scoreboard ...
 */
public class ScoreboardOverlayCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customui")
                        .then(Commands.literal("scoreboard").requires(src -> src.hasPermission(2))
                                // ==================== Team A ====================
                                .then(Commands.literal("teamA")
                                        .then(Commands.literal("icon")
                                                .then(Commands.argument("name", StringArgumentType.string())
                                                        .suggests((ctx, builder) -> {
                                                            // Suggest preset icon names
                                                            builder.suggest("sword");
                                                            builder.suggest("shield");
                                                            builder.suggest("crown");
                                                            builder.suggest("star");
                                                            builder.suggest("heart");
                                                            builder.suggest("skull");
                                                            builder.suggest("flag");
                                                            builder.suggest("diamond");
                                                            builder.suggest("fire");
                                                            builder.suggest("lightning");
                                                            builder.suggest("red");
                                                            builder.suggest("blue");
                                                            builder.suggest("green");
                                                            builder.suggest("yellow");
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> setTeamAIcon(ctx, StringArgumentType.getString(ctx, "name")))))
                                        .then(Commands.literal("barcolor")
                                                .then(Commands.argument("color", StringArgumentType.string())
                                                        .executes(ctx -> setTeamABarColor(ctx, StringArgumentType.getString(ctx, "color")))))
                                        // Progress: bind to scoreboard or set directly
                                        .then(Commands.literal("progress")
                                                // Direct value: /customui scoreboard teamA progress set <value>
                                                .then(Commands.literal("set")
                                                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                                                .executes(ScoreboardOverlayCommand::setTeamAProgressDirect)))
                                                // Bind to scoreboard: /customui scoreboard teamA progress bind <holder> <obj> <maxHolder> <maxObj>
                                                .then(Commands.literal("bind")
                                                        .then(Commands.argument("holder", StringArgumentType.string())
                                                                .then(Commands.argument("objective", StringArgumentType.string())
                                                                        .then(Commands.argument("maxHolder", StringArgumentType.string())
                                                                                .then(Commands.argument("maxObjective", StringArgumentType.string())
                                                                                        .executes(ScoreboardOverlayCommand::setTeamAProgressBind)))))))
                                        // Score: bind to scoreboard or set directly
                                        .then(Commands.literal("score")
                                                // Direct value: /customui scoreboard teamA score set <value>
                                                .then(Commands.literal("set")
                                                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                                                .executes(ScoreboardOverlayCommand::setTeamAScoreDirect)))
                                                // Bind to scoreboard: /customui scoreboard teamA score bind <holder> <obj>
                                                .then(Commands.literal("bind")
                                                        .then(Commands.argument("holder", StringArgumentType.string())
                                                                .then(Commands.argument("objective", StringArgumentType.string())
                                                                        .executes(ScoreboardOverlayCommand::setTeamAScoreBind))))))
                                // ==================== Team B ====================
                                .then(Commands.literal("teamB")
                                        .then(Commands.literal("icon")
                                                .then(Commands.argument("name", StringArgumentType.string())
                                                        .suggests((ctx, builder) -> {
                                                            // Suggest preset icon names
                                                            builder.suggest("sword");
                                                            builder.suggest("shield");
                                                            builder.suggest("crown");
                                                            builder.suggest("star");
                                                            builder.suggest("heart");
                                                            builder.suggest("skull");
                                                            builder.suggest("flag");
                                                            builder.suggest("diamond");
                                                            builder.suggest("fire");
                                                            builder.suggest("lightning");
                                                            builder.suggest("red");
                                                            builder.suggest("blue");
                                                            builder.suggest("green");
                                                            builder.suggest("yellow");
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> setTeamBIcon(ctx, StringArgumentType.getString(ctx, "name")))))
                                        .then(Commands.literal("barcolor")
                                                .then(Commands.argument("color", StringArgumentType.string())
                                                        .executes(ctx -> setTeamBBarColor(ctx, StringArgumentType.getString(ctx, "color")))))
                                        // Progress: bind to scoreboard or set directly
                                        .then(Commands.literal("progress")
                                                .then(Commands.literal("set")
                                                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                                                .executes(ScoreboardOverlayCommand::setTeamBProgressDirect)))
                                                .then(Commands.literal("bind")
                                                        .then(Commands.argument("holder", StringArgumentType.string())
                                                                .then(Commands.argument("objective", StringArgumentType.string())
                                                                        .then(Commands.argument("maxHolder", StringArgumentType.string())
                                                                                .then(Commands.argument("maxObjective", StringArgumentType.string())
                                                                                        .executes(ScoreboardOverlayCommand::setTeamBProgressBind)))))))
                                        // Score: bind to scoreboard or set directly
                                        .then(Commands.literal("score")
                                                .then(Commands.literal("set")
                                                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                                                .executes(ScoreboardOverlayCommand::setTeamBScoreDirect)))
                                                .then(Commands.literal("bind")
                                                        .then(Commands.argument("holder", StringArgumentType.string())
                                                                .then(Commands.argument("objective", StringArgumentType.string())
                                                                        .executes(ScoreboardOverlayCommand::setTeamBScoreBind))))))
                                .then(Commands.literal("reverse")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ScoreboardOverlayCommand::setProgressReverse)))
                                // ==================== Timer ====================
                                .then(Commands.literal("timer")
                                        // Direct value: /customui scoreboard timer set <ticks>
                                        .then(Commands.literal("set")
                                                .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                                                        .executes(ScoreboardOverlayCommand::setTimerDirect)))
                                        // Bind to scoreboard: /customui scoreboard timer bind <holder> <obj>
                                        .then(Commands.literal("bind")
                                                .then(Commands.argument("holder", StringArgumentType.string())
                                                        .then(Commands.argument("objective", StringArgumentType.string())
                                                                .executes(ScoreboardOverlayCommand::setTimerBind)))))
                                // ==================== Timer Color ====================
                                .then(Commands.literal("timercolor")
                                        // Preset colors: /customui scoreboard timercolor preset <name>
                                        .then(Commands.literal("preset")
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .executes(ScoreboardOverlayCommand::setTimerColorPreset)
                                                        .then(Commands.argument("duration", IntegerArgumentType.integer(1))
                                                                .executes(ScoreboardOverlayCommand::setTimerColorPresetCountdown))))
                                        // Custom color: /customui scoreboard timercolor <hex> [duration]
                                        .then(Commands.argument("color", StringArgumentType.string())
                                                .executes(ScoreboardOverlayCommand::setTimerColorSwitch)
                                                .then(Commands.argument("duration", IntegerArgumentType.integer(1))
                                                        .executes(ScoreboardOverlayCommand::setTimerColorCountdown))))
                                // ==================== Glow ====================
                                .then(Commands.literal("glow")
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    builder.suggest("none");
                                                    builder.suggest("change");
                                                    builder.suggest("leading");
                                                    return builder.buildFuture();
                                                })
                                                .executes(ScoreboardOverlayCommand::setGlowMode)))
                                // ==================== Visibility ====================
                                .then(Commands.literal("visibility")
                                        .then(Commands.literal("set")
                                                .then(Commands.argument("targets", EntityArgument.players())
                                                        .then(Commands.argument("visible", BoolArgumentType.bool())
                                                                .executes(ScoreboardOverlayCommand::setVisibility))))
                                        .then(Commands.literal("default")
                                                .then(Commands.argument("visible", BoolArgumentType.bool())
                                                        .executes(ScoreboardOverlayCommand::setDefaultVisibility))))
                                // ==================== Reset ====================
                                .then(Commands.literal("reset")
                                        .executes(ScoreboardOverlayCommand::resetAll)))
        );
    }

    // ==================== Implementation ====================

    private static int setTeamAIcon(CommandContext<CommandSourceStack> ctx, String path) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.teamAIconPath = path;
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team A icon set to: " + path), true);
        return 1;
    }

    private static int setTeamABarColor(CommandContext<CommandSourceStack> ctx, String colorHex) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        try {
            data.teamABarColor = (int) Long.parseLong(colorHex.replace("#", "").replace("0x", ""), 16);
            data.markConfigChanged();
            ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team A bar color set to: " + colorHex), true);
        } catch (NumberFormatException e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Invalid color format: " + colorHex));
            return 0;
        }
        return 1;
    }

    private static int setTeamAProgressBind(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.teamAProgressHolder = StringArgumentType.getString(ctx, "holder");
        data.teamAProgressObjective = StringArgumentType.getString(ctx, "objective");
        data.teamAMaxHolder = StringArgumentType.getString(ctx, "maxHolder");
        data.teamAMaxObjective = StringArgumentType.getString(ctx, "maxObjective");
        data.teamADirectProgress = -1f;  // Clear direct value, use binding
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team A progress bound to scoreboard"), true);
        return 1;
    }

    private static int setTeamAProgressDirect(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        int value = IntegerArgumentType.getInteger(ctx, "value");
        data.teamADirectProgress = value / 100f;  // Convert 0-100 to 0.0-1.0
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team A progress set to: " + value + "%"), true);
        return 1;
    }

    private static int setTeamAScoreBind(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.teamAScoreHolder = StringArgumentType.getString(ctx, "holder");
        data.teamAScoreObjective = StringArgumentType.getString(ctx, "objective");
        data.teamADirectScore = -1;  // Clear direct value, use binding
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team A score bound to scoreboard"), true);
        return 1;
    }

    private static int setTeamAScoreDirect(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        int value = IntegerArgumentType.getInteger(ctx, "value");
        data.teamADirectScore = value;
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team A score set to: " + value), true);
        return 1;
    }

    private static int setTeamBIcon(CommandContext<CommandSourceStack> ctx, String path) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.teamBIconPath = path;
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team B icon set to: " + path), true);
        return 1;
    }

    private static int setTeamBBarColor(CommandContext<CommandSourceStack> ctx, String colorHex) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        try {
            data.teamBBarColor = (int) Long.parseLong(colorHex.replace("#", "").replace("0x", ""), 16);
            data.markConfigChanged();
            ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team B bar color set to: " + colorHex), true);
        } catch (NumberFormatException e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Invalid color format: " + colorHex));
            return 0;
        }
        return 1;
    }

    private static int setTeamBProgressBind(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.teamBProgressHolder = StringArgumentType.getString(ctx, "holder");
        data.teamBProgressObjective = StringArgumentType.getString(ctx, "objective");
        data.teamBMaxHolder = StringArgumentType.getString(ctx, "maxHolder");
        data.teamBMaxObjective = StringArgumentType.getString(ctx, "maxObjective");
        data.teamBDirectProgress = -1f;  // Clear direct value, use binding
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team B progress bound to scoreboard"), true);
        return 1;
    }

    private static int setTeamBProgressDirect(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        int value = IntegerArgumentType.getInteger(ctx, "value");
        data.teamBDirectProgress = value / 100f;  // Convert 0-100 to 0.0-1.0
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team B progress set to: " + value + "%"), true);
        return 1;
    }

    private static int setTeamBScoreBind(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.teamBScoreHolder = StringArgumentType.getString(ctx, "holder");
        data.teamBScoreObjective = StringArgumentType.getString(ctx, "objective");
        data.teamBDirectScore = -1;  // Clear direct value, use binding
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team B score bound to scoreboard"), true);
        return 1;
    }

    private static int setTeamBScoreDirect(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        int value = IntegerArgumentType.getInteger(ctx, "value");
        data.teamBDirectScore = value;
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Team B score set to: " + value), true);
        return 1;
    }

    private static int setProgressReverse(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.reverseFillDirection = BoolArgumentType.getBool(ctx, "enabled");
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Progress reverse fill set to: " + data.reverseFillDirection), true);
        return 1;
    }

    private static int setTimerBind(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.timerHolder = StringArgumentType.getString(ctx, "holder");
        data.timerObjective = StringArgumentType.getString(ctx, "objective");
        data.timerDirectTicks = -1;  // Clear direct value, use binding
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Timer bound to scoreboard"), true);
        return 1;
    }

    private static int setTimerDirect(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
        data.timerDirectTicks = ticks;
        data.markConfigChanged();
        int seconds = ticks / 20;
        int mins = seconds / 60;
        int secs = seconds % 60;
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("[CustomUI] Timer set to: %02d:%02d (%d ticks)", mins, secs, ticks)), true);
        return 1;
    }

    private static int setTimerColorSwitch(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        String colorHex = StringArgumentType.getString(ctx, "color");
        try {
            int color = (int) Long.parseLong(colorHex.replace("#", "").replace("0x", ""), 16);
            // Ensure alpha channel
            if ((color & 0xFF000000) == 0) {
                color |= 0xFF000000;
            }
            data.timerColor = color;
            data.timerTempColor = color;
            data.timerTempDuration = 0;
            data.timerColorSwitch = true;
            data.markTimerColorChanged();
            ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Timer color set to: " + colorHex), true);
        } catch (NumberFormatException e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Invalid color format: " + colorHex));
            return 0;
        }
        return 1;
    }

    private static int setTimerColorCountdown(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        String colorHex = StringArgumentType.getString(ctx, "color");
        int duration = IntegerArgumentType.getInteger(ctx, "duration");
        try {
            int color = (int) Long.parseLong(colorHex.replace("#", "").replace("0x", ""), 16);
            // Ensure alpha channel
            if ((color & 0xFF000000) == 0) {
                color |= 0xFF000000;
            }
            data.timerTempColor = color;
            data.timerTempDuration = duration;
            data.timerColorSwitch = false;
            data.markTimerColorChanged();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Timer color set to: " + colorHex + " for " + duration + " ticks"), true);
        } catch (NumberFormatException e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Invalid color format: " + colorHex));
            return 0;
        }
        return 1;
    }

    private static int setTimerColorPreset(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        String presetName = StringArgumentType.getString(ctx, "name");
        int color = ScoreboardOverlayConfig.INSTANCE.getColorPreset(presetName);
        if (color == ScoreboardOverlayConfig.COLOR_NOT_FOUND) {
            ctx.getSource().sendFailure(Component.literal(
                    "[CustomUI] Unknown color preset: " + presetName +
                    ". Available: white, yellow, red, green, blue, orange, purple, cyan"));
            return 0;
        }
        data.timerColor = color;
        data.timerTempColor = color;
        data.timerTempDuration = 0;
        data.timerColorSwitch = true;
        data.markTimerColorChanged();
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Timer color set to preset: " + presetName), true);
        return 1;
    }

    private static int setTimerColorPresetCountdown(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        String presetName = StringArgumentType.getString(ctx, "name");
        int duration = IntegerArgumentType.getInteger(ctx, "duration");
        int color = ScoreboardOverlayConfig.INSTANCE.getColorPreset(presetName);
        if (color == ScoreboardOverlayConfig.COLOR_NOT_FOUND) {
            ctx.getSource().sendFailure(Component.literal(
                    "[CustomUI] Unknown color preset: " + presetName +
                    ". Available: white, yellow, red, green, blue, orange, purple, cyan"));
            return 0;
        }
        data.timerTempColor = color;
        data.timerTempDuration = duration;
        data.timerColorSwitch = false;
        data.markTimerColorChanged();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Timer color set to preset: " + presetName + " for " + duration + " ticks"), true);
        return 1;
    }

    private static int setGlowMode(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();

        // Validate mode
        if (!mode.equals("none") && !mode.equals("change") && !mode.equals("leading")) {
            ctx.getSource().sendFailure(Component.literal(
                    "[CustomUI] Invalid glow mode: " + mode + ". Use: none, change, or leading"));
            return 0;
        }

        data.glowMode = mode;
        data.markConfigChanged();

        String description = switch (mode) {
            case "none" -> "disabled";
            case "change" -> "flash on progress change";
            case "leading" -> "moving bar on progress change";
            default -> mode;
        };
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Glow mode set to: " + description), true);
        return 1;
    }

    private static int setVisibility(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        boolean visible = BoolArgumentType.getBool(ctx, "visible");
        try {
            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
            for (ServerPlayer player : targets) {
                data.setPlayerVisibility(player.getUUID(), visible);
                // Send config update to affected player
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new ScoreboardOverlayConfigPacket(
                                data.teamAIconPath, data.teamABarColor,
                                data.teamBIconPath, data.teamBBarColor,
                                data.glowMode, visible,
                                data.timerColor, data.timerTempColor,
                                data.timerTempDuration, data.timerColorSwitch,
                                data.reverseFillDirection
                        )
                );
            }
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Scoreboard visibility set to " + visible + " for " + targets.size() + " player(s)"), true);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Failed to set visibility: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int setDefaultVisibility(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        boolean visible = BoolArgumentType.getBool(ctx, "visible");
        data.defaultVisible = visible;
        data.playerVisibility.clear();
        data.visibilityOverrides.clear();
        data.markConfigChanged();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Default scoreboard visibility set to: " + visible), true);
        return 1;
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) {
        ScoreboardOverlayData data = getData(ctx);
        if (data == null) return 0;
        data.resetAll();
        // Force full resync to all players (will happen next tick via configVersion change)
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Scoreboard overlay reset to defaults"), true);
        return 1;
    }

    private static ScoreboardOverlayData getData(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        return ScoreboardOverlayData.get(server);
    }
}

