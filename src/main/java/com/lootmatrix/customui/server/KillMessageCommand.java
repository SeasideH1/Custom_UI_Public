package com.lootmatrix.customui.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /customui killMessages &lt;bool&gt;
 * /customui killMessageMode AllyTeam|AllyPlayer
 */
public final class KillMessageCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(KillMessageCommand.class);

    private KillMessageCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customui")
                        .then(Commands.literal("killMessages").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(KillMessageCommand::setKillMessages)))
                        .then(Commands.literal("killMessageMode").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("mode", StringArgumentType.string())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("AllyTeam");
                                            builder.suggest("AllyPlayer");
                                            return builder.buildFuture();
                                        })
                                        .executes(KillMessageCommand::setKillMessageMode)))
        );
        LOGGER.info("[CustomUI] Registered killMessages / killMessageMode commands");
    }

    private static int setKillMessageMode(CommandContext<CommandSourceStack> ctx) {
        String mode = StringArgumentType.getString(ctx, "mode");
        if (!"AllyTeam".equalsIgnoreCase(mode) && !"AllyPlayer".equalsIgnoreCase(mode)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[CustomUI] Invalid mode. Use 'AllyTeam' or 'AllyPlayer'"));
            return 0;
        }
        KillMessageServerState.setMode(mode, true);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Kill message mode set to '" + mode + "' for all players"), true);
        return 1;
    }

    private static int setKillMessages(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        KillMessageServerState.setEnabled(enabled, true);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Kill messages " + (enabled ? "enabled" : "disabled") + " for all players"), true);
        return 1;
    }
}
