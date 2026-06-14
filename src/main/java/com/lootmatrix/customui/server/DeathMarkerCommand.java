package com.lootmatrix.customui.server;

import com.lootmatrix.customui.entity.DeathMarkerEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * Commands for spawning death markers.
 * /customui deathmarker <x> <y> <z> <viewPlayers> [duration]
 */
public class DeathMarkerCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customui")
                        .then(Commands.literal("deathmarker").requires(src -> src.hasPermission(2))
                                // Spawn death marker at coordinates
                                // /customui deathmarker <pos> <viewers> [duration]
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .then(Commands.argument("viewers", EntityArgument.players())
                                                .executes(ctx -> spawnMarker(ctx, 100)) // default 100 ticks (5 seconds)
                                                .then(Commands.argument("duration", IntegerArgumentType.integer(1, 6000))
                                                        .executes(ctx -> spawnMarker(ctx, IntegerArgumentType.getInteger(ctx, "duration")))))))
        );
    }

    private static int spawnMarker(CommandContext<CommandSourceStack> ctx, int duration) {
        try {
            Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
            Collection<ServerPlayer> viewers = EntityArgument.getPlayers(ctx, "viewers");

            ServerLevel level = ctx.getSource().getLevel();

            // Create death marker entity
            DeathMarkerEntity marker = new DeathMarkerEntity(level, pos.x, pos.y, pos.z, duration);

            // Add viewers
            for (ServerPlayer viewer : viewers) {
                marker.addViewer(viewer.getUUID());
            }

            // Spawn entity
            level.addFreshEntity(marker);

            final int viewerCount = viewers.size();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Spawned death marker at " +
                    String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z) +
                    " visible to " + viewerCount + " player(s) for " + duration + " ticks"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Error: " + e.getMessage()));
            return 0;
        }
    }
}




