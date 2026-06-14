package com.lootmatrix.customui.ui;

import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.network.UITemplateSyncPacket;
import com.mojang.brigadier.CommandDispatcher;
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
 * Server-side commands for the UI template system.
 *
 * Usage:
 *   /customui ui open &lt;targets&gt; &lt;templateId&gt;
 *   /customui ui close &lt;targets&gt;
 *   /customui ui list
 *   /customui ui editor &lt;targets&gt; [templateId]
 */
public class UICommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("customui").then(uiRoot()));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> uiRoot() {
        return Commands.literal("ui").requires(src -> src.hasPermission(2))
                .then(Commands.literal("open")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("templateId", StringArgumentType.string())
                                        .executes(UICommand::openTemplate))))
                .then(Commands.literal("close")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(UICommand::closeTemplate)))
                .then(Commands.literal("list")
                        .executes(UICommand::listTemplates))
                .then(Commands.literal("editor")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(UICommand::openEditor)
                                .then(Commands.argument("templateId", StringArgumentType.string())
                                        .executes(UICommand::openEditorWithTemplate))));
    }

    private static int openTemplate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        String templateId = StringArgumentType.getString(ctx, "templateId");

        UITemplate template = UITemplateRegistry.getInstance().getTemplate(templateId);
        if (template == null) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Unknown template: " + templateId));
            return 0;
        }

        for (ServerPlayer player : targets) {
            Map<String, String> vars = UITemplateRegistry.getInstance().resolveVariables(template, player);
            UITemplateSyncPacket packet = new UITemplateSyncPacket(
                    UITemplateSyncPacket.Action.OPEN, template, vars);
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
            UITemplateRegistry.getInstance().openSession(player, templateId);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Opened UI '" + templateId + "' for " + targets.size() + " player(s)"), true);
        return 1;
    }

    private static int closeTemplate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");

        for (ServerPlayer player : targets) {
            UITemplateSyncPacket packet = new UITemplateSyncPacket(UITemplateSyncPacket.Action.CLOSE);
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
            UITemplateRegistry.getInstance().closeSession(player);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Closed UI for " + targets.size() + " player(s)"), true);
        return 1;
    }

    private static int listTemplates(CommandContext<CommandSourceStack> ctx) {
        Map<ResourceLocation, UITemplate> templates = UITemplateRegistry.getInstance().getTemplates();
        if (templates.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] No UI templates loaded"), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder("[CustomUI] Loaded UI templates:\n");
        for (var entry : templates.entrySet()) {
            UITemplate t = entry.getValue();
            sb.append("  ").append(entry.getKey())
                    .append(" (").append(t.canvasWidth).append("x").append(t.canvasHeight)
                    .append(", ").append(t.widgets.size()).append(" widgets)\n");
        }
        String result = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int openEditor(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");

        UITemplate empty = new UITemplate();
        empty.id = "editor_new";
        empty.title = "New Template";

        for (ServerPlayer player : targets) {
            UITemplateSyncPacket packet = new UITemplateSyncPacket(
                    UITemplateSyncPacket.Action.EDITOR, empty, Map.of());
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Opened UI editor for " + targets.size() + " player(s)"), true);
        return 1;
    }

    private static int openEditorWithTemplate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        String templateId = StringArgumentType.getString(ctx, "templateId");

        UITemplate template = UITemplateRegistry.getInstance().getTemplate(templateId);
        if (template == null) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Unknown template: " + templateId));
            return 0;
        }

        for (ServerPlayer player : targets) {
            UITemplateSyncPacket packet = new UITemplateSyncPacket(
                    UITemplateSyncPacket.Action.EDITOR, template, Map.of());
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Opened editor with '" + templateId + "' for " + targets.size() + " player(s)"), true);
        return 1;
    }
}
