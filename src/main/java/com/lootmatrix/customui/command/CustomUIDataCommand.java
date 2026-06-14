package com.lootmatrix.customui.command;

import com.lootmatrix.customui.server.MohistCompatUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Custom command for modifying player data that requires special handling.
 * Usage:
 *   /customui data motion <player> <x> <y> <z> - 设置绝对运动向量
 *   /customui data forwardMotion <player> <forward> <up> <strafe> - 根据玩家朝向设置相对运动
 *   /customui data directionMotion <player> <forward> <up> <strafe> - 根据玩家水平朝向设置运动，y轴为绝对值
 *   /customui data selectedItemSlot <player> <slot> - 设置选中的物品槽位
 */
public class CustomUIDataCommand {

    private static final int DATA_COMMAND_PERMISSION_LEVEL = 2;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customui")
                        .requires(source -> source.hasPermission(DATA_COMMAND_PERMISSION_LEVEL))
                        .then(Commands.literal("data")
                                .requires(source -> source.hasPermission(DATA_COMMAND_PERMISSION_LEVEL))
                                .then(Commands.literal("motion")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                        .executes(CustomUIDataCommand::setMotion)
                                                                )
                                                        )
                                                )
                                        )
                                )
                                .then(Commands.literal("forwardMotion")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("forward", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("up", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("strafe", DoubleArgumentType.doubleArg())
                                                                        .executes(CustomUIDataCommand::setForwardMotion)
                                                                )
                                                        )
                                                )
                                        )
                                )
                                .then(Commands.literal("directionMotion")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("forward", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("up", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("strafe", DoubleArgumentType.doubleArg())
                                                                        .executes(CustomUIDataCommand::setDirectionMotion)
                                                                )
                                                        )
                                                )
                                        )
                                )
                                .then(Commands.literal("selectedItemSlot")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 8))
                                                        .executes(CustomUIDataCommand::setSelectedItemSlot)
                                                )
                                        )
                                )
                        )
        );
    }

    private static int setMotion(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "target");
            double x = DoubleArgumentType.getDouble(context, "x");
            double y = DoubleArgumentType.getDouble(context, "y");
            double z = DoubleArgumentType.getDouble(context, "z");

            Vec3 motion = new Vec3(x, y, z);
            MohistCompatUtil.setMotion(target, motion);

            context.getSource().sendSuccess(
                    () -> Component.literal("§a已设置 " + target.getName().getString() + " 的 Motion 为 [" + x + ", " + y + ", " + z + "]"),
                    true
            );

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c设置 Motion 失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int setForwardMotion(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "target");
            double forward = DoubleArgumentType.getDouble(context, "forward");
            double up = DoubleArgumentType.getDouble(context, "up");
            double strafe = DoubleArgumentType.getDouble(context, "strafe");

            // 获取玩家的朝向向量
            Vec3 lookVec = target.getLookAngle();
            
            // 计算右向量（叉乘）
            Vec3 upVec = new Vec3(0, 1, 0);
            Vec3 rightVec = lookVec.cross(upVec).normalize();
            
            // 重新计算上向量以确保正交
            Vec3 trueUpVec = rightVec.cross(lookVec).normalize();
            
            // 组合运动向量：forward * 前向 + up * 上向 + strafe * 右向
            Vec3 motion = lookVec.scale(forward)
                    .add(trueUpVec.scale(up))
                    .add(rightVec.scale(strafe));

            MohistCompatUtil.setMotion(target, motion);

            context.getSource().sendSuccess(
                    () -> Component.literal("§a已设置 " + target.getName().getString() + " 的 ForwardMotion 为 [forward=" + forward + ", up=" + up + ", strafe=" + strafe + "]" +
                            " (实际Motion: [" + String.format("%.3f", motion.x) + ", " + String.format("%.3f", motion.y) + ", " + String.format("%.3f", motion.z) + "])"),
                    true
            );

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c设置 ForwardMotion 失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int setDirectionMotion(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "target");
            double forward = DoubleArgumentType.getDouble(context, "forward");
            double up = DoubleArgumentType.getDouble(context, "up");
            double strafe = DoubleArgumentType.getDouble(context, "strafe");

            // 获取玩家的水平朝向（忽略俯仰角）
            float yaw = target.getYRot();
            double yawRad = Math.toRadians(yaw);
            
            // 计算水平前向向量
            Vec3 forwardVec = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
            
            // 计算水平右向向量
            Vec3 rightVec = new Vec3(Math.cos(yawRad), 0, Math.sin(yawRad));
            
            // 组合运动向量：forward * 水平前向 + strafe * 水平右向，y轴使用绝对值
            Vec3 horizontalMotion = forwardVec.scale(forward).add(rightVec.scale(strafe));
            Vec3 motion = new Vec3(horizontalMotion.x, up, horizontalMotion.z);

            MohistCompatUtil.setMotion(target, motion);

            context.getSource().sendSuccess(
                    () -> Component.literal("§a已设置 " + target.getName().getString() + " 的 DirectionMotion 为 [forward=" + forward + ", up=" + up + ", strafe=" + strafe + "]" +
                            " (实际Motion: [" + String.format("%.3f", motion.x) + ", " + String.format("%.3f", motion.y) + ", " + String.format("%.3f", motion.z) + "])"),
                    true
            );

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c设置 DirectionMotion 失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int setSelectedItemSlot(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "target");
            int slot = IntegerArgumentType.getInteger(context, "slot");

            MohistCompatUtil.setSelectedSlot(target, slot);

            context.getSource().sendSuccess(
                    () -> Component.literal("§a已设置 " + target.getName().getString() + " 的 SelectedItemSlot 为 " + slot),
                    true
            );

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("§c设置 SelectedItemSlot 失败: " + e.getMessage()));
            return 0;
        }
    }
}
