package com.lootmatrix.customui.cinematic;

import com.lootmatrix.customui.network.CinematicCameraPacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-side command for the cinematic camera system.
 */
public class CinematicCameraCommand {

    private static final Map<String, String> EASING_DESCRIPTIONS = createEasingDescriptions();
    private static final Map<String, String> INTERP_DESCRIPTIONS = createInterpDescriptions();
    private static final SuggestionProvider<CommandSourceStack> PRESET_SUGGESTIONS = (ctx, builder) -> {
        suggestPathReferences(builder, false);
        return builder.buildFuture();
    };
    private static final SuggestionProvider<CommandSourceStack> TARGET_REF_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("@s", new LiteralMessage("current player"));
        builder.suggest("@p", new LiteralMessage("nearest player"));
        builder.suggest("@a", new LiteralMessage("all players"));
        for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            builder.suggest(player.getGameProfile().getName(), new LiteralMessage("online player"));
        }
        suggestPathReferences(builder, true);
        return builder.buildFuture();
    };
    private static final SuggestionProvider<CommandSourceStack> EASING_SUGGESTIONS = enumSuggestions(EASING_DESCRIPTIONS);
    private static final SuggestionProvider<CommandSourceStack> INTERP_SUGGESTIONS = enumSuggestions(INTERP_DESCRIPTIONS);
    private static final SuggestionProvider<CommandSourceStack> LOOP_SUGGESTIONS =
            boolSuggestions("loop playback until manually stopped", "play once and stop");
    private static final SuggestionProvider<CommandSourceStack> MERGE_SUGGESTIONS =
            boolSuggestions("merge into the next continuous curve span", "treat the next boundary as a hard cut");
    private static final SuggestionProvider<CommandSourceStack> SHOW_SELF_SUGGESTIONS =
            boolSuggestions("render the local player model", "hide the local player model");
    private static final SuggestionProvider<CommandSourceStack> HIDE_HUD_SUGGESTIONS =
            boolSuggestions("hide HUD during this segment", "keep HUD visible");
    private static final SuggestionProvider<CommandSourceStack> NIGHT_VISION_SUGGESTIONS =
            boolSuggestions("apply night vision during this segment", "do not apply night vision");
    private static final SuggestionProvider<CommandSourceStack> ABSOLUTE_POS_SUGGESTIONS =
            boolSuggestions("use absolute world coordinates", "offset from player position when playback starts");
    private static final SuggestionProvider<CommandSourceStack> DURATION_SUGGESTIONS =
            valueSuggestions(Map.of("0", "control point / end immediately", "20", "1 second", "40", "2 seconds", "60", "3 seconds", "100", "5 seconds"));
    private static final SuggestionProvider<CommandSourceStack> ROLL_SUGGESTIONS =
            valueSuggestions(Map.of("0", "keep level horizon", "10", "slight clockwise roll", "-10", "slight counter-clockwise roll"));
    private static final SuggestionProvider<CommandSourceStack> FOV_SUGGESTIONS =
            valueSuggestions(Map.of("50", "telephoto / narrow", "70", "default cinematic FOV", "90", "wide shot"));
    private static final SuggestionProvider<CommandSourceStack> SEND_CHUNK_RADIUS_SUGGESTIONS =
            valueSuggestions(Map.of("0", "disable camera chunk streaming", "6", "small radius", "8", "default cinematic radius", "10", "large radius", "12", "very large radius"));

    /** Builder state keyed by path target ref (selector/player/json id). */
    private static final Map<String, List<CameraKeyframe>> PATH_BUILDERS = new LinkedHashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("customui").then(cameraRoot()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cameraRoot() {
        return Commands.literal("camera").requires(src -> src.hasPermission(2))
                .then(Commands.literal("play")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("presetId", StringArgumentType.word())
                                        .suggests(PRESET_SUGGESTIONS)
                                        .executes(CinematicCameraCommand::playPreset))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(CinematicCameraCommand::stopCamera)))
                .then(Commands.literal("list")
                        .executes(CinematicCameraCommand::listPresets))
                .then(Commands.literal("path")
                        .then(Commands.argument("targetRef", StringArgumentType.word())
                                .suggests(TARGET_REF_SUGGESTIONS)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .then(Commands.argument("lookAt", Vec3Argument.vec3())
                                                        .then(Commands.argument("duration", IntegerArgumentType.integer(0, 72000))
                                                                .suggests(DURATION_SUGGESTIONS)
                                                                .executes(CinematicCameraCommand::addKeyframe)
                                                                .then(lookAtOptions())))))
                                .then(Commands.literal("addAngles")
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180, 180))
                                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90, 90))
                                                                .then(Commands.argument("duration", IntegerArgumentType.integer(0, 72000))
                                                                        .suggests(DURATION_SUGGESTIONS)
                                                                        .executes(CinematicCameraCommand::addKeyframeAngles)
                                                                        .then(angleOptions()))))))
                                .then(Commands.literal("start")
                                        .executes(ctx -> startPath(ctx, false))
                                        .then(Commands.argument("loop", BoolArgumentType.bool())
                                                .suggests(LOOP_SUGGESTIONS)
                                                .executes(ctx -> startPath(ctx, true))))
                                .then(Commands.literal("clear")
                                        .executes(CinematicCameraCommand::clearPath))
                                .then(Commands.literal("info")
                                        .executes(CinematicCameraCommand::pathInfo))));
    }

    /**
     * Optional parameter chain for lookAt-based keyframes:
     * roll → posPathInterp → oriPathInterp → posMoveEasing → oriMoveEasing → fov →
     * posPathMerge → oriPathMerge → posMoveMerge → oriMoveMerge →
     * showSelf → sendChunksRadius → hideHud → nightVision → absolutePos
     */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> lookAtOptions() {
        return Commands.argument("roll", FloatArgumentType.floatArg(-180, 180))
                .suggests(ROLL_SUGGESTIONS)
                .executes(CinematicCameraCommand::addKeyframe)
                .then(Commands.argument("posPathInterp", StringArgumentType.string())
                        .suggests(INTERP_SUGGESTIONS)
                        .executes(CinematicCameraCommand::addKeyframe)
                        .then(Commands.argument("oriPathInterp", StringArgumentType.string())
                                .suggests(INTERP_SUGGESTIONS)
                                .executes(CinematicCameraCommand::addKeyframe)
                                .then(Commands.argument("posMoveEasing", StringArgumentType.string())
                                        .suggests(EASING_SUGGESTIONS)
                                        .executes(CinematicCameraCommand::addKeyframe)
                                        .then(Commands.argument("oriMoveEasing", StringArgumentType.string())
                                                .suggests(EASING_SUGGESTIONS)
                                                .executes(CinematicCameraCommand::addKeyframe)
                                                .then(Commands.argument("fov", FloatArgumentType.floatArg(1, 179))
                                                        .suggests(FOV_SUGGESTIONS)
                                                        .executes(CinematicCameraCommand::addKeyframe)
                                                        .then(optionalFlags(false)))))));
    }

    /**
     * Optional parameter chain for angle-based keyframes (same structure as lookAtOptions).
     */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> angleOptions() {
        return Commands.argument("roll", FloatArgumentType.floatArg(-180, 180))
                .suggests(ROLL_SUGGESTIONS)
                .executes(CinematicCameraCommand::addKeyframeAngles)
                .then(Commands.argument("posPathInterp", StringArgumentType.string())
                        .suggests(INTERP_SUGGESTIONS)
                        .executes(CinematicCameraCommand::addKeyframeAngles)
                        .then(Commands.argument("oriPathInterp", StringArgumentType.string())
                                .suggests(INTERP_SUGGESTIONS)
                                .executes(CinematicCameraCommand::addKeyframeAngles)
                                .then(Commands.argument("posMoveEasing", StringArgumentType.string())
                                        .suggests(EASING_SUGGESTIONS)
                                        .executes(CinematicCameraCommand::addKeyframeAngles)
                                        .then(Commands.argument("oriMoveEasing", StringArgumentType.string())
                                                .suggests(EASING_SUGGESTIONS)
                                                .executes(CinematicCameraCommand::addKeyframeAngles)
                                                .then(Commands.argument("fov", FloatArgumentType.floatArg(1, 179))
                                                        .suggests(FOV_SUGGESTIONS)
                                                        .executes(CinematicCameraCommand::addKeyframeAngles)
                                                        .then(optionalFlags(true)))))));
    }

    /**
     * Optional flags tail: 4 merge bools → showSelf → sendChunksRadius → hideHud → nightVision → absolutePos
     */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> optionalFlags(boolean angleMode) {
        return Commands.argument("posPathMerge", BoolArgumentType.bool())
                .suggests(MERGE_SUGGESTIONS)
                .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)
                .then(Commands.argument("oriPathMerge", BoolArgumentType.bool())
                        .suggests(MERGE_SUGGESTIONS)
                        .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)
                        .then(Commands.argument("posMoveMerge", BoolArgumentType.bool())
                                .suggests(MERGE_SUGGESTIONS)
                                .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)
                                .then(Commands.argument("oriMoveMerge", BoolArgumentType.bool())
                                        .suggests(MERGE_SUGGESTIONS)
                                        .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)
                                        .then(Commands.argument("showSelf", BoolArgumentType.bool())
                                                .suggests(SHOW_SELF_SUGGESTIONS)
                                                .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)
                                                .then(Commands.argument("sendChunksRadius", IntegerArgumentType.integer(0, 32))
                                                        .suggests(SEND_CHUNK_RADIUS_SUGGESTIONS)
                                                        .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)
                                                        .then(Commands.argument("hideHud", BoolArgumentType.bool())
                                                                .suggests(HIDE_HUD_SUGGESTIONS)
                                                                .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)
                                                                .then(Commands.argument("nightVision", BoolArgumentType.bool())
                                                                        .suggests(NIGHT_VISION_SUGGESTIONS)
                                                                        .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)
                                                                        .then(Commands.argument("absolutePos", BoolArgumentType.bool())
                                                                                .suggests(ABSOLUTE_POS_SUGGESTIONS)
                                                                                .executes(angleMode ? CinematicCameraCommand::addKeyframeAngles : CinematicCameraCommand::addKeyframe)))))))));
    }

    private static int playPreset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        String presetIdInput = StringArgumentType.getString(ctx, "presetId");
        ResourceLocation resolvedPresetId = CameraPathLoader.resolveLoadedPathId(presetIdInput);
        CameraPath path = resolvedPresetId != null ? CameraPathLoader.getPath(resolvedPresetId) : null;
        if (path == null) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Unknown camera preset: " + presetIdInput));
            return 0;
        }

        CinematicCameraPacket packet = new CinematicCameraPacket(CinematicCameraPacket.Action.START, path);
        for (ServerPlayer player : targets) {
            CinematicChunkStreamingManager.start(player, path);
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }

        final String resolvedPresetLabel = resolvedPresetId.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Started camera preset '" + resolvedPresetLabel + "' for " + targets.size() + " player(s)"), true);
        return 1;
    }

    private static int stopCamera(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        CinematicCameraPacket packet = new CinematicCameraPacket();
        for (ServerPlayer player : targets) {
            CinematicChunkStreamingManager.stop(player);
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Stopped camera for " + targets.size() + " player(s)"), true);
        return 1;
    }

    private static int listPresets(CommandContext<CommandSourceStack> ctx) {
        Map<ResourceLocation, CameraPath> paths = CameraPathLoader.getLoadedPaths();
        if (paths.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] No camera presets loaded"), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder("[CustomUI] Loaded camera presets:\n");
        for (Map.Entry<ResourceLocation, CameraPath> entry : paths.entrySet()) {
            CameraPath path = entry.getValue();
            sb.append("  ").append(entry.getKey())
                    .append(" (").append(path.getKeyframes().size()).append(" keyframes, ")
                    .append(path.getTotalDurationTicks()).append(" ticks")
                    .append(path.isLoop() ? ", loop" : "")
                    .append(")\n");
        }
        String result = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int addKeyframe(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        Vec3 lookAt = Vec3Argument.getVec3(ctx, "lookAt");
        return addKeyframeInternal(ctx, pos, lookAt, 0f, 0f);
    }

    private static int addKeyframeAngles(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        float yaw = FloatArgumentType.getFloat(ctx, "yaw");
        float pitch = FloatArgumentType.getFloat(ctx, "pitch");
        return addKeyframeInternal(ctx, pos, null, yaw, pitch);
    }

    private static int addKeyframeInternal(CommandContext<CommandSourceStack> ctx,
                                           Vec3 pos, Vec3 lookAt, float yaw, float pitch) throws CommandSyntaxException {
        String targetRef = StringArgumentType.getString(ctx, "targetRef");
        String builderKey = builderKey(ctx.getSource(), targetRef);

        int durationTicks = IntegerArgumentType.getInteger(ctx, "duration");
        float roll = getOptionalFloat(ctx, "roll", 0f);

        // 4-channel interpolation
        CameraKeyframe.InterpolationMode posPathInterp = parseInterp(
                getOptionalString(ctx, "posPathInterp", "CENTRIPETAL_CATMULL_ROM"));
        CameraKeyframe.InterpolationMode oriPathInterp = parseInterp(
                getOptionalString(ctx, "oriPathInterp", "CENTRIPETAL_CATMULL_ROM"));
        CameraKeyframe.EasingType posMoveEasing = parseEasing(
                getOptionalString(ctx, "posMoveEasing", "EASE_IN_OUT"));
        CameraKeyframe.EasingType oriMoveEasing = parseEasing(
                getOptionalString(ctx, "oriMoveEasing", "EASE_IN_OUT"));

        float fov = getOptionalFloat(ctx, "fov", 70f);

        // 4 merge booleans
        boolean posPathMerge = getOptionalBool(ctx, "posPathMerge", true);
        boolean oriPathMerge = getOptionalBool(ctx, "oriPathMerge", true);
        boolean posMoveMerge = getOptionalBool(ctx, "posMoveMerge", true);
        boolean oriMoveMerge = getOptionalBool(ctx, "oriMoveMerge", true);

        boolean showSelf = getOptionalBool(ctx, "showSelf", false);
        int sendChunksRadius = Math.max(0, getOptionalInt(ctx, "sendChunksRadius", 0));
        boolean hideHud = getOptionalBool(ctx, "hideHud", true);
        boolean nightVision = getOptionalBool(ctx, "nightVision", false);
        boolean absolutePos = getOptionalBool(ctx, "absolutePos", true);

        CameraKeyframe keyframe = new CameraKeyframe(
                pos, lookAt, yaw, pitch, roll,
                posPathInterp, posPathMerge,
                posMoveEasing, posMoveMerge,
                oriPathInterp, oriPathMerge,
                oriMoveEasing, oriMoveMerge,
                fov,
                durationTicks,
                absolutePos,
                nightVision,
                sendChunksRadius > 0,
                hideHud,
                showSelf,
                sendChunksRadius
        );

        PATH_BUILDERS.computeIfAbsent(builderKey, ignored -> new ArrayList<>()).add(keyframe);
        int index = PATH_BUILDERS.get(builderKey).size();

        String summary = "[CustomUI] Added keyframe #" + index + " to " + targetRef
                + " at " + formatVec3(pos)
                + (lookAt != null ? " lookAt=" + formatVec3(lookAt) : " yaw=" + formatFloat(yaw) + " pitch=" + formatFloat(pitch))
                + " duration=" + durationTicks + "t (" + formatSeconds(durationTicks) + "s)"
                + " roll=" + formatFloat(roll)
                + " posPath=" + posPathInterp + " oriPath=" + oriPathInterp
                + " posEase=" + posMoveEasing + " oriEase=" + oriMoveEasing
                + " fov=" + formatFloat(fov)
                + " flags=" + describeFlags(keyframe);
        ctx.getSource().sendSuccess(() -> Component.literal(summary), false);
        return 1;
    }

    private static int startPath(CommandContext<CommandSourceStack> ctx, boolean hasLoopArg) throws CommandSyntaxException {
        String targetRef = StringArgumentType.getString(ctx, "targetRef");
        String builderKey = builderKey(ctx.getSource(), targetRef);
        boolean loop = hasLoopArg && BoolArgumentType.getBool(ctx, "loop");

        List<CameraKeyframe> keyframes = PATH_BUILDERS.get(builderKey);
        if (keyframes == null || keyframes.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[CustomUI] No keyframes added. Use '/customui camera path <目标玩家/目标json> add ...' first."));
            return 0;
        }

        Collection<ServerPlayer> players = resolveTargetPlayers(ctx.getSource(), targetRef);
        CameraPath path = new CameraPath(pathIdForTarget(targetRef), keyframes, loop);

        if (!players.isEmpty()) {
            CinematicCameraPacket packet = new CinematicCameraPacket(CinematicCameraPacket.Action.START, path);
            for (ServerPlayer player : players) {
                CinematicChunkStreamingManager.start(player, path);
                ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
            int count = players.size();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Started inline camera path (" + keyframes.size() + " keyframes) for "
                            + count + " player(s)"), true);
            return 1;
        }

        ResourceLocation pathId = CameraPathLoader.canonicalizePathId(targetRef);
        if (pathId != null) {
            CameraPathLoader.registerRuntimePath(pathId, path);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Registered runtime camera path '" + pathId + "' (" + keyframes.size() + " keyframes)"), true);
            return 1;
        }

        ctx.getSource().sendFailure(Component.literal(
                "[CustomUI] '" + targetRef + "' is neither an online player target nor a valid path id."));
        return 0;
    }

    private static int clearPath(CommandContext<CommandSourceStack> ctx) {
        String targetRef = StringArgumentType.getString(ctx, "targetRef");
        PATH_BUILDERS.remove(builderKey(ctx.getSource(), targetRef));
        ResourceLocation pathId = CameraPathLoader.canonicalizePathId(targetRef);
        if (pathId != null) {
            CameraPathLoader.unregisterRuntimePath(pathId);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Camera path builder cleared: " + targetRef), false);
        return 1;
    }

    private static int pathInfo(CommandContext<CommandSourceStack> ctx) {
        String targetRef = StringArgumentType.getString(ctx, "targetRef");
        List<CameraKeyframe> keyframes = PATH_BUILDERS.get(builderKey(ctx.getSource(), targetRef));
        if (keyframes == null || keyframes.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] Path builder is empty"), false);
            return 0;
        }

        int totalTicks = 0;
        for (CameraKeyframe keyframe : keyframes) {
            totalTicks += Math.max(keyframe.durationTicks, 0);
        }

        int count = keyframes.size();
        final int totalTicksFinal = totalTicks;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Path builder '" + targetRef + "': " + count + " keyframe(s), "
                        + totalTicksFinal + " ticks total (" + formatSeconds(totalTicksFinal) + "s)"), false);

        for (int i = 0; i < keyframes.size(); i++) {
            final String summary = describeKeyframe(i + 1, keyframes.get(i));
            ctx.getSource().sendSuccess(() -> Component.literal(summary), false);
        }
        return 1;
    }

    private static String describeKeyframe(int index, CameraKeyframe kf) {
        return "  #" + index
                + " pos=" + formatVec3(kf.position)
                + ' '
                + (kf.lookAt != null
                    ? "lookAt=" + formatVec3(kf.lookAt)
                    : "angles=(" + formatFloat(kf.yaw) + ", " + formatFloat(kf.pitch) + ')')
                + " duration=" + kf.durationTicks + "t"
                + (kf.durationTicks == 0 ? "(control-only)" : "")
                + " roll=" + formatFloat(kf.roll)
                + " fov=" + formatFloat(kf.fov)
                + " posPath=" + kf.positionPathInterp + (kf.positionPathMerge ? "[M]" : "")
                + " oriPath=" + kf.orientationPathInterp + (kf.orientationPathMerge ? "[M]" : "")
                + " posEase=" + kf.positionMoveEasing + (kf.positionMoveMerge ? "[M]" : "")
                + " oriEase=" + kf.orientationMoveEasing + (kf.orientationMoveMerge ? "[M]" : "")
                + " flags=" + describeFlags(kf);
    }

    private static String describeFlags(CameraKeyframe keyframe) {
        List<String> flags = new ArrayList<>();
        flags.add(keyframe.absolutePosition ? "absolute" : "relative");
        if (keyframe.showSelf) flags.add("showSelf");
        if (keyframe.sendChunks) flags.add("sendChunks:" + keyframe.sendChunksRadius);
        if (keyframe.hideHud) flags.add("hideHud");
        if (keyframe.nightVision) flags.add("nightVision");
        return String.join("|", flags);
    }

    private static Collection<ServerPlayer> resolveTargetPlayers(CommandSourceStack source, String targetRef) {
        try {
            EntitySelector selector = new EntitySelectorParser(new StringReader(targetRef), true).parse();
            return selector.findPlayers(source);
        } catch (CommandSyntaxException ignored) {
            ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(targetRef);
            return player != null ? List.of(player) : List.of();
        }
    }

    private static String builderKey(CommandSourceStack source, String targetRef) {
        Collection<ServerPlayer> targets = resolveTargetPlayers(source, targetRef);
        if (!targets.isEmpty()) {
            return targetRef;
        }

        ResourceLocation pathId = CameraPathLoader.canonicalizePathId(targetRef);
        return pathId != null ? pathId.toString() : targetRef;
    }

    private static String pathIdForTarget(String targetRef) {
        ResourceLocation id = CameraPathLoader.canonicalizePathId(targetRef);
        return id != null ? id.toString() : "inline_" + sanitizeTargetRef(targetRef);
    }

    private static String sanitizeTargetRef(String targetRef) {
        return targetRef.replaceAll("[^a-zA-Z0-9:_-]", "_");
    }

    private static CameraKeyframe.EasingType parseEasing(String value) {
        try {
            return CameraKeyframe.EasingType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return CameraKeyframe.EasingType.EASE_IN_OUT;
        }
    }

    private static CameraKeyframe.InterpolationMode parseInterp(String value) {
        try {
            return CameraKeyframe.InterpolationMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return CameraKeyframe.InterpolationMode.CENTRIPETAL_CATMULL_ROM;
        }
    }

    private static float getOptionalFloat(CommandContext<CommandSourceStack> ctx, String key, float def) {
        try { return FloatArgumentType.getFloat(ctx, key); } catch (Exception ignored) { return def; }
    }

    private static int getOptionalInt(CommandContext<CommandSourceStack> ctx, String key, int def) {
        try { return IntegerArgumentType.getInteger(ctx, key); } catch (Exception ignored) { return def; }
    }

    private static boolean getOptionalBool(CommandContext<CommandSourceStack> ctx, String key, boolean def) {
        try { return BoolArgumentType.getBool(ctx, key); } catch (Exception ignored) { return def; }
    }

    private static String getOptionalString(CommandContext<CommandSourceStack> ctx, String key, String def) {
        try { return StringArgumentType.getString(ctx, key); } catch (Exception ignored) { return def; }
    }

    private static String formatVec3(Vec3 value) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", value.x, value.y, value.z);
    }

    private static String formatSeconds(int ticks) {
        return formatFloat(ticks / 20f);
    }

    private static String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static SuggestionProvider<CommandSourceStack> enumSuggestions(Map<String, String> descriptions) {
        return (ctx, builder) -> {
            for (Map.Entry<String, String> entry : descriptions.entrySet()) {
                builder.suggest(entry.getKey(), new LiteralMessage(entry.getValue()));
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> valueSuggestions(Map<String, String> descriptions) {
        return enumSuggestions(descriptions);
    }

    private static SuggestionProvider<CommandSourceStack> boolSuggestions(String trueDescription, String falseDescription) {
        return (ctx, builder) -> {
            builder.suggest("true", new LiteralMessage(trueDescription));
            builder.suggest("false", new LiteralMessage(falseDescription));
            return builder.buildFuture();
        };
    }

    private static Map<String, String> createEasingDescriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("NONE", "constant speed through the segment");
        descriptions.put("EASE_IN", "slow start, then accelerate");
        descriptions.put("EASE_OUT", "fast start, then decelerate");
        descriptions.put("EASE_IN_OUT", "soft start and soft stop");
        descriptions.put("EASE_IN_CUBIC", "strong cubic acceleration");
        descriptions.put("EASE_OUT_CUBIC", "strong cubic deceleration");
        descriptions.put("EASE_IN_OUT_CUBIC", "strong cubic ease on both ends");
        descriptions.put("EASE_IN_BACK", "pull back slightly before accelerating");
        descriptions.put("EASE_OUT_BACK", "overshoot slightly before settling");
        descriptions.put("EASE_IN_OUT_BACK", "back-overshoot on both ends");
        return descriptions;
    }

    private static Map<String, String> createInterpDescriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("LINEAR", "straight line between keyframes");
        descriptions.put("CATMULL_ROM", "smooth Catmull-Rom spline (uniform)");
        descriptions.put("CENTRIPETAL_CATMULL_ROM", "smooth Catmull-Rom spline (centripetal, avoids cusps)");
        descriptions.put("CUBIC_BEZIER", "auto-tangent cubic Bézier curve");
        descriptions.put("HERMITE", "Hermite spline with auto-tangents");
        descriptions.put("INSTANT", "snap to this keyframe immediately");
        return descriptions;
    }

    private static void suggestPathReferences(com.mojang.brigadier.suggestion.SuggestionsBuilder builder, boolean includeBuilderRefs) {
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<ResourceLocation, CameraPath> entry : CameraPathLoader.getLoadedPaths().entrySet()) {
            ResourceLocation id = entry.getKey();
            CameraPath path = entry.getValue();
            String description = path.getKeyframes().size() + " keyframes, "
                    + path.getTotalDurationTicks() + " ticks"
                    + (path.isLoop() ? ", loop" : "");

            if (seen.add(id.toString())) {
                builder.suggest(id.toString(), new LiteralMessage(description));
            }

            String bareName = id.getPath();
            if (seen.add(bareName)) {
                builder.suggest(bareName, new LiteralMessage("alias of " + id));
            }
        }

        if (!includeBuilderRefs) {
            return;
        }

        for (Map.Entry<String, List<CameraKeyframe>> entry : PATH_BUILDERS.entrySet()) {
            String key = entry.getKey();
            if (!seen.add(key)) {
                continue;
            }
            builder.suggest(key, new LiteralMessage("builder with " + entry.getValue().size() + " keyframe(s)"));
        }
    }
}
