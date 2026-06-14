package com.lootmatrix.customui.atmosphere;

import com.lootmatrix.customui.network.AtmospherePacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.Collection;
import java.util.Map;

/**
 * Server-side command for the atmosphere system.
 *
 * Usage:
 *   /customui atmosphere apply <targets> <presetId>
 *   /customui atmosphere clear <targets>
 *   /customui atmosphere list
 *   /customui atmosphere fog <targets> <r> <g> <b> <near> <far> [fadeInTicks]
 */
public class AtmosphereCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customui")
                        .then(atmosphereRoot())
        );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> atmosphereRoot() {
        return Commands.literal("atmosphere").requires(src -> src.hasPermission(2))
                // /customui atmosphere apply <targets> <presetId>
                .then(Commands.literal("apply")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("presetId", StringArgumentType.string())
                                        .executes(AtmosphereCommand::applyPreset))))
                // /customui atmosphere clear <targets>
                .then(Commands.literal("clear")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(AtmosphereCommand::clearAtmosphere)))
                // /customui atmosphere list
                .then(Commands.literal("list")
                        .executes(AtmosphereCommand::listPresets))
                // /customui atmosphere fog <targets> <r> <g> <b> <near> <far> [fadeInTicks]
                .then(Commands.literal("fog")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("r", FloatArgumentType.floatArg(0, 1))
                                        .then(Commands.argument("g", FloatArgumentType.floatArg(0, 1))
                                                .then(Commands.argument("b", FloatArgumentType.floatArg(0, 1))
                                                        .then(Commands.argument("near", FloatArgumentType.floatArg(0, 1024))
                                                                .then(Commands.argument("far", FloatArgumentType.floatArg(0, 1024))
                                                                        .executes(AtmosphereCommand::fogInline)
                                                                        .then(Commands.argument("fadeInTicks", IntegerArgumentType.integer(0, 6000))
                                                                                .executes(AtmosphereCommand::fogInlineWithFade)))))))));
    }

    // ==================== Command Executors ====================

    private static int applyPreset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        String presetId = StringArgumentType.getString(ctx, "presetId");

        AtmospherePreset preset = AtmospherePresetLoader.getPreset(presetId);
        if (preset == null) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Unknown atmosphere preset: " + presetId));
            return 0;
        }

        AtmospherePacket packet = new AtmospherePacket(AtmospherePacket.Action.APPLY, preset);
        for (ServerPlayer player : targets) {
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Applied atmosphere '" + presetId + "' to " + targets.size() + " player(s)"), true);
        return 1;
    }

    private static int clearAtmosphere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");

        AtmospherePacket packet = new AtmospherePacket();
        for (ServerPlayer player : targets) {
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Cleared atmosphere for " + targets.size() + " player(s)"), true);
        return 1;
    }

    private static int listPresets(CommandContext<CommandSourceStack> ctx) {
        Map<ResourceLocation, AtmospherePreset> presets = AtmospherePresetLoader.getLoadedPresets();
        if (presets.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] No atmosphere presets loaded"), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder("[CustomUI] Loaded atmosphere presets:\n");
        for (Map.Entry<ResourceLocation, AtmospherePreset> entry : presets.entrySet()) {
            AtmospherePreset p = entry.getValue();
            sb.append("  ").append(entry.getKey())
                    .append(" (fadeIn=").append(p.fadeInTicks).append("t")
                    .append(", fadeOut=").append(p.fadeOutTicks).append("t")
                    .append(p.fog != null ? ", fog" : "")
                    .append(p.sky != null ? ", sky=" + p.sky.type : "")
                    .append(p.sun != null ? ", sun" : "")
                    .append(p.moon != null ? ", moon" : "")
                    .append(p.stars != null ? ", stars" : "")
                    .append(p.clouds != null ? ", clouds" : "")
                    .append(")\n");
        }
        String result = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /**
     * Inline fog-only atmosphere preset (no JSON needed).
     */
    private static int fogInline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return fogInlineInternal(ctx, 40);
    }

    private static int fogInlineWithFade(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int fadeIn = IntegerArgumentType.getInteger(ctx, "fadeInTicks");
        return fogInlineInternal(ctx, fadeIn);
    }

    private static int fogInlineInternal(CommandContext<CommandSourceStack> ctx, int fadeInTicks) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        float r = FloatArgumentType.getFloat(ctx, "r");
        float g = FloatArgumentType.getFloat(ctx, "g");
        float b = FloatArgumentType.getFloat(ctx, "b");
        float near = FloatArgumentType.getFloat(ctx, "near");
        float far = FloatArgumentType.getFloat(ctx, "far");

        AtmospherePreset.FogConfig fogConfig = new AtmospherePreset.FogConfig(
                r, g, b, near, far, AtmospherePreset.FogShapeType.SPHERE, false, 1.0f);
        AtmospherePreset preset = new AtmospherePreset(
                "inline_fog", fadeInTicks, 20, AtmospherePreset.EasingType.EASE_IN_OUT,
                fogConfig, null, null, null, null, null, null);

        AtmospherePacket packet = new AtmospherePacket(AtmospherePacket.Action.APPLY, preset);
        for (ServerPlayer player : targets) {
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("[CustomUI] Applied fog (%.2f,%.2f,%.2f) near=%.0f far=%.0f to %d player(s)",
                        r, g, b, near, far, targets.size())), true);
        return 1;
    }
}
