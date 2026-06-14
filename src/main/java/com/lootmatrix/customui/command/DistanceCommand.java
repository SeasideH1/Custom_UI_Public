package com.lootmatrix.customui.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 距离计算指令（Forge 版，仿 Datapack-Extension CalculateDistance）
 *
 * <pre>
 * /customui distance &lt;e2&gt;                              — 命令源位置到目标实体（米，整数）
 * /customui distance &lt;e2&gt; cm                             — 命令源位置到目标实体（厘米，整数）
 * /customui distance &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;       — 两点坐标（米，整数）
 * /customui distance &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; cm      — 两点坐标（厘米，整数）
 * </pre>
 *
 * 实体模式使用 {@link CommandSourceStack#getPosition()} 作为起点，兼容
 * {@code execute positioned / execute at} 指定坐标后再计算到实体的距离，例如：
 * {@code execute positioned 0 64 0 run customui distance @p}
 *
 * 返回值：距离整数，可供 {@code execute store result score} 使用。
 */
public final class DistanceCommand {

    private DistanceCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customui")
                        .then(Commands.literal("distance")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("e2", EntityArgument.entity())
                                        .executes(DistanceCommand::entityDistanceMeters)
                                        .then(Commands.literal("cm")
                                                .executes(DistanceCommand::entityDistanceCentimeters)))
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("y1", IntegerArgumentType.integer())
                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                .executes(DistanceCommand::relativeDistanceMeters)
                                                                                .then(Commands.literal("cm")
                                                                                        .executes(DistanceCommand::relativeDistanceCentimeters))))))))
        ));
    }

    private static int entityDistanceMeters(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Vec3 from = ctx.getSource().getPosition();
        Entity e2 = EntityArgument.getEntity(ctx, "e2");
        double distance = distanceBetween(from, e2.position());
        int value = (int) distance;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("距离: %.2f", distance)), false);
        return value;
    }

    private static int entityDistanceCentimeters(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Vec3 from = ctx.getSource().getPosition();
        Entity e2 = EntityArgument.getEntity(ctx, "e2");
        double distanceCm = distanceBetween(from, e2.position()) * 100.0;
        int value = (int) distanceCm;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("距离: %.2f (cm)", distanceCm)), false);
        return value;
    }

    private static int relativeDistanceMeters(CommandContext<CommandSourceStack> ctx) {
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int y1 = IntegerArgumentType.getInteger(ctx, "y1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int y2 = IntegerArgumentType.getInteger(ctx, "y2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");

        int dx = x2 - x1;
        int dy = y2 - y1;
        int dz = z2 - z1;
        int value = (int) Math.sqrt((long) dx * dx + (long) dy * dy + (long) dz * dz);

        ctx.getSource().sendSuccess(
                () -> Component.literal("RelativeDistance = %s (meters)".formatted(value)), false);
        return value;
    }

    private static int relativeDistanceCentimeters(CommandContext<CommandSourceStack> ctx) {
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int y1 = IntegerArgumentType.getInteger(ctx, "y1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int y2 = IntegerArgumentType.getInteger(ctx, "y2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");

        int dx = x2 - x1;
        int dy = y2 - y1;
        int dz = z2 - z1;
        double distanceCm = Math.sqrt((long) dx * dx + (long) dy * dy + (long) dz * dz) * 100.0;
        int value = (int) distanceCm;

        ctx.getSource().sendSuccess(
                () -> Component.literal("RelativeDistance = %s (centimeters)".formatted(value)), false);
        return value;
    }

    private static double distanceBetween(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
