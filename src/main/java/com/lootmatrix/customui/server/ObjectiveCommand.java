package com.lootmatrix.customui.server;

import com.lootmatrix.customui.hud.HudAnchor;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.network.TitleImagePacket;
import com.lootmatrix.customui.network.TitlePacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Commands for custom titles.
 */
public class ObjectiveCommand {

    /** 9-grid anchor/origin keyword suggestions for titleex/titleimage. */
    private static CompletableFuture<Suggestions> suggestAnchors(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        for (HudAnchor anchor : HudAnchor.values()) {
            builder.suggest(anchor.toJsonName());
        }
        return builder.buildFuture();
    }

    /** Optional trailing string argument; null when absent. */
    @Nullable
    private static String getOptionalString(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** anchor/origin ordinal from an optional keyword; -1 = not provided (legacy layout). */
    private static int parseAnchorId(@Nullable String value) {
        return value == null ? -1 : HudAnchor.fromString(value).ordinal();
    }

    /**
     * Suggestion provider for icon presets.
     */
    private static CompletableFuture<Suggestions> suggestIconPresets(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        // Add preset names
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
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customui")
                        // ==================== Title ====================
                        .then(Commands.literal("title").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> sendTitle(ctx, 0xFFFFFFFF, 1f, 1f, 0f, 0f, 500, 3000, 500, 0)))))
                        // Title with options
                        .then(Commands.literal("titleex").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("text", StringArgumentType.string())
                                                .then(Commands.argument("color", StringArgumentType.string())
                                                        .then(Commands.argument("alpha", FloatArgumentType.floatArg(0, 1))
                                                                .then(Commands.argument("scale", FloatArgumentType.floatArg(0.1f, 5f))
                                                                        .then(Commands.argument("offsetX", FloatArgumentType.floatArg(-500, 500))
                                                                                .then(Commands.argument("offsetY", FloatArgumentType.floatArg(-500, 500))
                                                                                        .then(Commands.argument("fadeIn", IntegerArgumentType.integer(0, 10000))
                                                                                                .then(Commands.argument("stay", IntegerArgumentType.integer(0, 60000))
                                                                                                        .then(Commands.argument("fadeOut", IntegerArgumentType.integer(0, 10000))
                                                                                                                .then(Commands.argument("line", IntegerArgumentType.integer(0, 10))
                                                                                                                        .executes(ObjectiveCommand::sendTitleEx)
                                                                                                                        .then(Commands.argument("anchor", StringArgumentType.word())
                                                                                                                                .suggests(ObjectiveCommand::suggestAnchors)
                                                                                                                                .executes(ObjectiveCommand::sendTitleEx)
                                                                                                                                .then(Commands.argument("origin", StringArgumentType.word())
                                                                                                                                        .suggests(ObjectiveCommand::suggestAnchors)
                                                                                                                                        .executes(ObjectiveCommand::sendTitleEx)))))))))))))))
                        // ==================== Title with Image ====================
                        .then(Commands.literal("titleimage").requires(src -> src.hasPermission(2))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("icon", StringArgumentType.string())
                                                .suggests(ObjectiveCommand::suggestIconPresets)
                                                .executes(ctx -> sendTitleImage(ctx, 32, 1f, 0f, 0f, 500, 3000, 500))
                                                .then(Commands.argument("size", IntegerArgumentType.integer(8, 128))
                                                        .then(Commands.argument("alpha", FloatArgumentType.floatArg(0, 1))
                                                                .then(Commands.argument("offsetX", FloatArgumentType.floatArg(-500, 500))
                                                                        .then(Commands.argument("offsetY", FloatArgumentType.floatArg(-500, 500))
                                                                                .then(Commands.argument("fadeIn", IntegerArgumentType.integer(0, 10000))
                                                                                        .then(Commands.argument("stay", IntegerArgumentType.integer(0, 60000))
                                                                                                .then(Commands.argument("fadeOut", IntegerArgumentType.integer(0, 10000))
                                                                                                        .executes(ObjectiveCommand::sendTitleImageEx)
                                                                                                        .then(Commands.argument("anchor", StringArgumentType.word())
                                                                                                                .suggests(ObjectiveCommand::suggestAnchors)
                                                                                                                .executes(ObjectiveCommand::sendTitleImageEx)
                                                                                                                .then(Commands.argument("origin", StringArgumentType.word())
                                                                                                                        .suggests(ObjectiveCommand::suggestAnchors)
                                                                                                                        .executes(ObjectiveCommand::sendTitleImageEx))))))))))))
        ));
    }

    private static int sendTitle(CommandContext<CommandSourceStack> ctx, int color, float alpha, float scale,
                                  float offsetX, float offsetY, long fadeIn, long stay, long fadeOut, int line) {
        try {
            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
            String text = StringArgumentType.getString(ctx, "text");

            for (ServerPlayer player : targets) {
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new TitlePacket(text, color, alpha, scale, offsetX, offsetY, fadeIn, stay, fadeOut, line, -1, -1)
                );
            }

            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Sent title to " + targets.size() + " player(s)"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int sendTitleEx(CommandContext<CommandSourceStack> ctx) {
        try {
            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
            String text = StringArgumentType.getString(ctx, "text");
            String colorStr = StringArgumentType.getString(ctx, "color");
            float alpha = FloatArgumentType.getFloat(ctx, "alpha");
            float scale = FloatArgumentType.getFloat(ctx, "scale");
            float offsetX = FloatArgumentType.getFloat(ctx, "offsetX");
            float offsetY = FloatArgumentType.getFloat(ctx, "offsetY");
            int fadeIn = IntegerArgumentType.getInteger(ctx, "fadeIn");
            int stay = IntegerArgumentType.getInteger(ctx, "stay");
            int fadeOut = IntegerArgumentType.getInteger(ctx, "fadeOut");
            int line = IntegerArgumentType.getInteger(ctx, "line");
            int anchorId = parseAnchorId(getOptionalString(ctx, "anchor"));
            int originId = parseAnchorId(getOptionalString(ctx, "origin"));

            // Parse color
            int color = parseColor(colorStr);

            for (ServerPlayer player : targets) {
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new TitlePacket(text, color, alpha, scale, offsetX, offsetY, fadeIn, stay, fadeOut, line,
                                anchorId, originId)
                );
            }

            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Sent title to " + targets.size() + " player(s)"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int parseColor(String colorStr) {
        try {
            String hex = colorStr.replace("#", "").replace("0x", "");
            int color = (int) Long.parseLong(hex, 16);
            if (hex.length() <= 6) {
                color |= 0xFF000000;
            }
            return color;
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    private static int sendTitleImage(CommandContext<CommandSourceStack> ctx, int size, float alpha,
                                       float offsetX, float offsetY, long fadeIn, long stay, long fadeOut) {
        try {
            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
            String iconPath = StringArgumentType.getString(ctx, "icon");

            for (ServerPlayer player : targets) {
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new TitleImagePacket(iconPath, size, alpha, offsetX, offsetY, fadeIn, stay, fadeOut, -1, -1)
                );
            }

            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Sent title image to " + targets.size() + " player(s)"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int sendTitleImageEx(CommandContext<CommandSourceStack> ctx) {
        try {
            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
            String iconPath = StringArgumentType.getString(ctx, "icon");
            int size = IntegerArgumentType.getInteger(ctx, "size");
            float alpha = FloatArgumentType.getFloat(ctx, "alpha");
            float offsetX = FloatArgumentType.getFloat(ctx, "offsetX");
            float offsetY = FloatArgumentType.getFloat(ctx, "offsetY");
            int fadeIn = IntegerArgumentType.getInteger(ctx, "fadeIn");
            int stay = IntegerArgumentType.getInteger(ctx, "stay");
            int fadeOut = IntegerArgumentType.getInteger(ctx, "fadeOut");
            int anchorId = parseAnchorId(getOptionalString(ctx, "anchor"));
            int originId = parseAnchorId(getOptionalString(ctx, "origin"));

            for (ServerPlayer player : targets) {
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new TitleImagePacket(iconPath, size, alpha, offsetX, offsetY, fadeIn, stay, fadeOut,
                                anchorId, originId)
                );
            }

            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Sent title image to " + targets.size() + " player(s)"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Error: " + e.getMessage()));
            return 0;
        }
    }
}
