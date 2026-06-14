package com.lootmatrix.customui.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * 视线检测指令（Forge 版，仿 Datapack-Extension CanSeeCommand）
 *
 * <pre>
 * /customui cansee as &lt;viewer&gt; &lt;targets...&gt; [fov] [strict] [raycast]
 * /customui cansee at &lt;anchor&gt; &lt;targets...&gt; [fov] [strict] [raycast]
 * /customui cansee execute &lt;targets...&gt; [fov] [strict] [raycast]
 * </pre>
 *
 * <ul>
 *   <li><b>as</b> — 使用指定实体的眼睛位置与朝向</li>
 *   <li><b>at</b> — 使用锚点实体的眼睛位置，朝向取自命令执行者</li>
 *   <li><b>execute</b> — 使用当前命令源实体作为观察者</li>
 * </ul>
 *
 * 返回值：全部可见时返回可见数量；任一不可见时返回 0。
 */
public final class CanSeeCommand {

    private CanSeeCommand() {}

    private static final double DEFAULT_FOV = 70.0;
    private static final double DEFAULT_STRICT_FOV = 10.0;
    private static final double MAX_DISTANCE = 256.0;

    private static final SimpleCommandExceptionType NO_ENTITY_EXECUTOR =
            new SimpleCommandExceptionType(Component.literal("命令执行者必须是实体"));

    private enum ViewerMode {
        AS, AT, EXECUTE
    }

    private record ViewContext(Vec3 eyePos, Vec3 lookDir) {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customui")
                        .then(Commands.literal("cansee")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("as")
                                        .then(Commands.argument("viewer", EntityArgument.entity())
                                                .then(buildTargetsNode(ViewerMode.AS))))
                                .then(Commands.literal("at")
                                        .then(Commands.argument("anchor", EntityArgument.entity())
                                                .then(buildTargetsNode(ViewerMode.AT))))
                                .then(buildTargetsNode(ViewerMode.EXECUTE))
                        )
        );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildTargetsNode(ViewerMode mode) {
        return Commands.argument("targets", EntityArgument.entities())
                .executes(ctx -> canSee(ctx, mode, DEFAULT_FOV, false, false))
                .then(buildFovBranch(mode))
                .then(buildStrictBranch(mode, DEFAULT_STRICT_FOV))
                .then(buildRaycastBranch(mode, DEFAULT_FOV));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildFovBranch(ViewerMode mode) {
        return Commands.argument("fov", DoubleArgumentType.doubleArg(1.0, 180.0))
                .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), false, false))
                .then(Commands.literal("strict")
                        .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), true, false))
                        .then(Commands.literal("raycast")
                                .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), true, true))))
                .then(Commands.literal("raycast")
                        .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), false, true))
                        .then(Commands.literal("strict")
                                .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), true, true))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildStrictBranch(ViewerMode mode, double defaultFov) {
        return Commands.literal("strict")
                .executes(ctx -> canSee(ctx, mode, defaultFov, true, false))
                .then(Commands.argument("fov", DoubleArgumentType.doubleArg(1.0, 180.0))
                        .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), true, false))
                        .then(Commands.literal("raycast")
                                .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), true, true))))
                .then(Commands.literal("raycast")
                        .executes(ctx -> canSee(ctx, mode, defaultFov, true, true))
                        .then(Commands.argument("fov", DoubleArgumentType.doubleArg(1.0, 180.0))
                                .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), true, true))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildRaycastBranch(ViewerMode mode, double defaultFov) {
        return Commands.literal("raycast")
                .executes(ctx -> canSee(ctx, mode, defaultFov, false, true))
                .then(Commands.argument("fov", DoubleArgumentType.doubleArg(1.0, 180.0))
                        .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), false, true))
                        .then(Commands.literal("strict")
                                .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), true, true))))
                .then(Commands.literal("strict")
                        .executes(ctx -> canSee(ctx, mode, defaultFov, true, true))
                        .then(Commands.argument("fov", DoubleArgumentType.doubleArg(1.0, 180.0))
                                .executes(ctx -> canSee(ctx, mode, DoubleArgumentType.getDouble(ctx, "fov"), true, true))));
    }

    private static int canSee(CommandContext<CommandSourceStack> ctx, ViewerMode mode,
                              double fovDegrees, boolean strict, boolean useRaycast)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ViewContext view = resolveViewContext(ctx, mode);
        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");

        if (targets.isEmpty()) {
            source.sendFailure(Component.literal("未找到目标实体"));
            return 0;
        }

        double halfFovRad = Math.toRadians(fovDegrees / 2.0);
        double cosHalfFov = Math.cos(halfFovRad);

        int visibleCount = 0;
        for (Entity target : targets) {
            if (isSameViewer(mode, ctx, target)) {
                continue;
            }

            boolean canSeeTarget = strict
                    ? canSeeStrict(view.eyePos(), view.lookDir(), target, cosHalfFov, useRaycast)
                    : canSeeNormal(view.eyePos(), view.lookDir(), target, cosHalfFov, useRaycast);

            if (canSeeTarget) {
                visibleCount++;
            } else {
                final String targetName = target.getName().getString();
                source.sendSuccess(
                        () -> Component.literal(String.format("无法看到: %s", targetName)),
                        false);
                return 0;
            }
        }

        final int count = visibleCount;
        final String modeLabel = viewerModeLabel(mode);
        final String detectMode = strict ? "严格模式" : "普通模式";
        final String raycastInfo = useRaycast ? ", 方块遮挡检测" : "";
        source.sendSuccess(
                () -> Component.literal(String.format(
                        "可以看到所有 %d 个目标实体 (%s, %s, FOV: %.1f°%s)",
                        count, modeLabel, detectMode, fovDegrees, raycastInfo)),
                false);

        return visibleCount;
    }

    private static ViewContext resolveViewContext(CommandContext<CommandSourceStack> ctx, ViewerMode mode)
            throws CommandSyntaxException {
        return switch (mode) {
            case AS -> {
                Entity viewer = EntityArgument.getEntity(ctx, "viewer");
                yield new ViewContext(
                        viewer.getEyePosition(),
                        viewer.getViewVector(1.0F).normalize());
            }
            case AT -> {
                Entity anchor = EntityArgument.getEntity(ctx, "anchor");
                Entity executor = ctx.getSource().getEntity();
                Vec3 lookDir = executor != null
                        ? executor.getViewVector(1.0F).normalize()
                        : anchor.getViewVector(1.0F).normalize();
                yield new ViewContext(anchor.getEyePosition(), lookDir);
            }
            case EXECUTE -> {
                Entity executor = ctx.getSource().getEntity();
                if (executor == null) {
                    throw NO_ENTITY_EXECUTOR.create();
                }
                yield new ViewContext(
                        executor.getEyePosition(),
                        executor.getViewVector(1.0F).normalize());
            }
        };
    }

    private static boolean isSameViewer(ViewerMode mode, CommandContext<CommandSourceStack> ctx, Entity target)
            throws CommandSyntaxException {
        return switch (mode) {
            case AS -> target == EntityArgument.getEntity(ctx, "viewer");
            case AT -> target == EntityArgument.getEntity(ctx, "anchor");
            case EXECUTE -> {
                Entity executor = ctx.getSource().getEntity();
                yield executor != null && target == executor;
            }
        };
    }

    private static String viewerModeLabel(ViewerMode mode) {
        return switch (mode) {
            case AS -> "as";
            case AT -> "at";
            case EXECUTE -> "execute";
        };
    }

    private static boolean canSeeNormal(Vec3 eyePos, Vec3 lookDir, Entity target,
                                        double cosHalfFov, boolean useRaycast) {
        if (eyePos.distanceTo(target.position()) > MAX_DISTANCE) {
            return false;
        }

        AABB targetBox = target.getBoundingBox();
        Vec3[] checkPoints = {
                targetBox.getCenter(),
                new Vec3(targetBox.minX, targetBox.minY, targetBox.minZ),
                new Vec3(targetBox.maxX, targetBox.minY, targetBox.minZ),
                new Vec3(targetBox.minX, targetBox.maxY, targetBox.minZ),
                new Vec3(targetBox.maxX, targetBox.maxY, targetBox.minZ),
                new Vec3(targetBox.minX, targetBox.minY, targetBox.maxZ),
                new Vec3(targetBox.maxX, targetBox.minY, targetBox.maxZ),
                new Vec3(targetBox.minX, targetBox.maxY, targetBox.maxZ),
                new Vec3(targetBox.maxX, targetBox.maxY, targetBox.maxZ)
        };

        for (Vec3 point : checkPoints) {
            if (isPointInFov(eyePos, lookDir, point, cosHalfFov)
                    && (!useRaycast || hasLineOfSight(target, eyePos, point))) {
                return true;
            }
        }
        return false;
    }

    private static boolean canSeeStrict(Vec3 eyePos, Vec3 lookDir, Entity target,
                                        double cosHalfFov, boolean useRaycast) {
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        if (eyePos.distanceTo(targetCenter) > MAX_DISTANCE) {
            return false;
        }
        if (!isPointInFov(eyePos, lookDir, targetCenter, cosHalfFov)) {
            return false;
        }
        return !useRaycast || hasLineOfSight(target, eyePos, targetCenter);
    }

    private static boolean isPointInFov(Vec3 eyePos, Vec3 lookDir, Vec3 targetPoint, double cosHalfFov) {
        Vec3 toTarget = targetPoint.subtract(eyePos).normalize();
        return lookDir.dot(toTarget) >= cosHalfFov;
    }

    private static boolean hasLineOfSight(Entity contextEntity, Vec3 eyePos, Vec3 targetPoint) {
        var clipResult = contextEntity.level().clip(new ClipContext(
                eyePos,
                targetPoint,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                contextEntity
        ));
        return clipResult.getType() == HitResult.Type.MISS
                || clipResult.getLocation().distanceToSqr(targetPoint) < 1.0;
    }
}
