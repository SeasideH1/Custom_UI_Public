package com.lootmatrix.customui.hud;

import com.lootmatrix.customui.network.HudPlayPacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

/**
 * /customui hud ... — playback and editor entry points for HUD templates.
 *
 *   /customui hud play &lt;targets&gt; &lt;templateId&gt;
 *   /customui hud seek &lt;targets&gt; &lt;templateId&gt; &lt;tick&gt;     (adjust active keyframe stage)
 *   /customui hud stop &lt;targets&gt; &lt;templateId&gt;
 *   /customui hud stopall &lt;targets&gt;
 *   /customui hud list
 *   /customui hud sync &lt;targets&gt;                          (re-push template cache)
 *   /customui hud editor [templateId]                      (opens the visual editor)
 */
public class HudCommand {

    private static final SuggestionProvider<CommandSourceStack> TEMPLATE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    HudTemplateRegistry.getInstance().getAll().keySet(), builder);

    /** Only screenType="gui" templates (for /customui gui ...). */
    private static final SuggestionProvider<CommandSourceStack> GUI_TEMPLATE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    HudTemplateRegistry.getInstance().getAll().entrySet().stream()
                            .filter(e -> e.getValue().isGui())
                            .map(Map.Entry::getKey),
                    builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("customui").then(hudRoot()).then(guiRoot()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> guiRoot() {
        return Commands.literal("gui").requires(src -> src.hasPermission(2))
                .then(Commands.literal("open")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("templateId", ResourceLocationArgument.id())
                                        .suggests(GUI_TEMPLATE_SUGGESTIONS)
                                        .executes(HudCommand::guiOpen))))
                .then(Commands.literal("close")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(HudCommand::guiClose)))
                .then(Commands.literal("activate")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("templateId", ResourceLocationArgument.id())
                                        .suggests(GUI_TEMPLATE_SUGGESTIONS)
                                        .executes(ctx -> guiSetActivated(ctx, true)))))
                .then(Commands.literal("deactivate")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("templateId", ResourceLocationArgument.id())
                                        .suggests(GUI_TEMPLATE_SUGGESTIONS)
                                        .executes(ctx -> guiSetActivated(ctx, false)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> hudRoot() {
        return Commands.literal("hud").requires(src -> src.hasPermission(2))
                .then(Commands.literal("play")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("templateId", ResourceLocationArgument.id())
                                        .suggests(TEMPLATE_SUGGESTIONS)
                                        .executes(HudCommand::play))))
                .then(Commands.literal("seek")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("templateId", ResourceLocationArgument.id())
                                        .suggests(TEMPLATE_SUGGESTIONS)
                                        .then(Commands.argument("tick", IntegerArgumentType.integer(0))
                                                .executes(HudCommand::seek)))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("templateId", ResourceLocationArgument.id())
                                        .suggests(TEMPLATE_SUGGESTIONS)
                                        .executes(HudCommand::stop))))
                .then(Commands.literal("stopall")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(HudCommand::stopAll)))
                .then(Commands.literal("list")
                        .executes(HudCommand::list))
                .then(Commands.literal("sync")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(HudCommand::sync)))
                .then(Commands.literal("editor")
                        .executes(ctx -> editor(ctx, null))
                        .then(Commands.argument("templateId", ResourceLocationArgument.id())
                                .suggests(TEMPLATE_SUGGESTIONS)
                                .executes(ctx -> editor(ctx, ResourceLocationArgument.getId(ctx, "templateId")))));
    }

    /**
     * Resolve a command-supplied id. Brigadier defaults bare paths ("foo") to the
     * "minecraft" namespace, so fall back to path-only lookup in that case.
     */
    @Nullable
    private static HudTemplate lookup(ResourceLocation id) {
        HudTemplateRegistry registry = HudTemplateRegistry.getInstance();
        HudTemplate template = registry.get(id.toString());
        if (template == null && ResourceLocation.DEFAULT_NAMESPACE.equals(id.getNamespace())) {
            template = registry.get(id.getPath());
        }
        return template;
    }

    private static int play(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        ResourceLocation templateId = ResourceLocationArgument.getId(ctx, "templateId");

        HudTemplate template = lookup(templateId);
        if (template == null) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Unknown HUD template: " + templateId));
            return 0;
        }
        if (template.isGui()) {
            // GUI templates must open as an interactive screen (mouse released),
            // never as a passive overlay; treat "hud play" as "gui open".
            for (ServerPlayer player : targets) {
                GuiSessionManager.forceOpen(player, template);
                send(player, new HudPlayPacket(HudPlayPacket.Action.GUI_OPEN, template.id, 0));
            }
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[CustomUI] Opened GUI '" + template.id + "' for " + targets.size() + " player(s)"), true);
            return targets.size();
        }
        for (ServerPlayer player : targets) {
            send(player, new HudPlayPacket(HudPlayPacket.Action.PLAY, template.id, 0));
            HudTemplateRegistry.getInstance().onPlay(player, template.id);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Playing HUD '" + template.id + "' for " + targets.size() + " player(s)"), true);
        return targets.size();
    }

    private static int guiOpen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        ResourceLocation templateId = ResourceLocationArgument.getId(ctx, "templateId");

        HudTemplate template = lookup(templateId);
        if (template == null) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Unknown HUD template: " + templateId));
            return 0;
        }
        if (!template.isGui()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[CustomUI] Template '" + template.id + "' is not a GUI (screenType=gui)"));
            return 0;
        }
        for (ServerPlayer player : targets) {
            // Command-driven opens bypass the activation gate
            GuiSessionManager.forceOpen(player, template);
            send(player, new HudPlayPacket(HudPlayPacket.Action.GUI_OPEN, template.id, 0));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Opened GUI '" + template.id + "' for " + targets.size() + " player(s)"), true);
        return targets.size();
    }

    private static int guiSetActivated(CommandContext<CommandSourceStack> ctx, boolean activate)
            throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        ResourceLocation templateId = ResourceLocationArgument.getId(ctx, "templateId");

        HudTemplate template = lookup(templateId);
        if (template == null || !template.isGui()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[CustomUI] Unknown GUI template: " + templateId));
            return 0;
        }
        HudPlayPacket.Action action = activate
                ? HudPlayPacket.Action.GUI_ACTIVATE : HudPlayPacket.Action.GUI_DEACTIVATE;
        for (ServerPlayer player : targets) {
            if (activate) {
                GuiSessionManager.activate(player, template.id);
            } else {
                GuiSessionManager.deactivate(player, template.id);
            }
            send(player, new HudPlayPacket(action, template.id, 0));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] " + (activate ? "Activated" : "Deactivated") + " GUI '" + template.id
                        + "' for " + targets.size() + " player(s)"), true);
        return targets.size();
    }

    private static int guiClose(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        for (ServerPlayer player : targets) {
            String open = GuiSessionManager.openGui(player);
            if (open != null) {
                GuiSessionManager.onClose(player, open);
            }
            send(player, new HudPlayPacket(HudPlayPacket.Action.GUI_CLOSE, "", 0));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Closed GUI for " + targets.size() + " player(s)"), true);
        return targets.size();
    }

    private static int seek(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        ResourceLocation templateId = ResourceLocationArgument.getId(ctx, "templateId");
        int tick = IntegerArgumentType.getInteger(ctx, "tick");

        HudTemplate template = lookup(templateId);
        if (template == null) {
            ctx.getSource().sendFailure(Component.literal("[CustomUI] Unknown HUD template: " + templateId));
            return 0;
        }
        for (ServerPlayer player : targets) {
            send(player, new HudPlayPacket(HudPlayPacket.Action.SEEK, template.id, tick));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Seeked HUD '" + template.id + "' to tick " + tick), true);
        return targets.size();
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        ResourceLocation templateId = ResourceLocationArgument.getId(ctx, "templateId");

        HudTemplate template = lookup(templateId);
        String resolvedId = template != null ? template.id : templateId.toString();
        for (ServerPlayer player : targets) {
            if (template != null && template.isGui()) {
                // Mirror of the play() redirect: stopping a GUI closes its screen
                if (resolvedId.equals(GuiSessionManager.openGui(player))) {
                    GuiSessionManager.onClose(player, resolvedId);
                }
                send(player, new HudPlayPacket(HudPlayPacket.Action.GUI_CLOSE, resolvedId, 0));
                continue;
            }
            send(player, new HudPlayPacket(HudPlayPacket.Action.STOP, resolvedId, 0));
            HudTemplateRegistry.getInstance().onStop(player, resolvedId);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Stopped HUD '" + resolvedId + "'"), true);
        return targets.size();
    }

    private static int stopAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        for (ServerPlayer player : targets) {
            send(player, new HudPlayPacket(HudPlayPacket.Action.STOP_ALL, "", 0));
            HudTemplateRegistry.getInstance().onStopAll(player);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Stopped all HUD templates for " + targets.size() + " player(s)"), true);
        return targets.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        Map<String, HudTemplate> templates = HudTemplateRegistry.getInstance().getAll();
        if (templates.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[CustomUI] No HUD templates loaded"), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("[CustomUI] HUD templates (" + templates.size() + "):");
        for (HudTemplate t : templates.values()) {
            sb.append("\n  ").append(t.id)
                    .append(" (").append(t.elements.size()).append(" elements, lifetime=")
                    .append(t.isPersistent() ? "persistent" : t.effectiveLifetime() + "t")
                    .append(t.loop ? ", loop" : "").append(")");
        }
        String result = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return templates.size();
    }

    private static int sync(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        HudTemplateRegistry.getInstance().loadFromWorld(ctx.getSource().getServer());
        for (ServerPlayer player : targets) {
            HudTemplateRegistry.getInstance().syncAllTo(player);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[CustomUI] Re-synced HUD templates to " + targets.size() + " player(s)"), true);
        return targets.size();
    }

    private static int editor(CommandContext<CommandSourceStack> ctx, @Nullable ResourceLocation templateId)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        HudTemplateRegistry.getInstance().loadFromWorld(ctx.getSource().getServer());
        HudTemplateRegistry.getInstance().syncAllTo(player);
        String resolvedId = "";
        if (templateId != null) {
            HudTemplate template = lookup(templateId);
            if (template == null) {
                ctx.getSource().sendFailure(Component.literal("[CustomUI] Unknown HUD template: " + templateId));
                return 0;
            }
            resolvedId = template.id;
        }
        send(player, new HudPlayPacket(HudPlayPacket.Action.EDITOR, resolvedId, 0));
        return 1;
    }

    private static void send(ServerPlayer player, HudPlayPacket packet) {
        ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
