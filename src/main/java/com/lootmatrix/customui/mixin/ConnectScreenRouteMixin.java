package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.route.ServerRouteManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import javax.annotation.Nullable;

/**
 * Swaps the connect target for the best-scoring route (lowest combined
 * latency / packet-loss / jitter) measured by {@link ServerRouteManager},
 * when the server has alternates configured and auto-select is on.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenRouteMixin {

    @ModifyVariable(method = "startConnecting", at = @At("HEAD"), argsOnly = true)
    private static ServerAddress customui$pickBestRoute(ServerAddress address,
                                                        Screen parent, Minecraft minecraft,
                                                        ServerAddress sameAddress,
                                                        @Nullable ServerData serverData,
                                                        boolean quickPlay) {
        if (serverData == null) {
            return address;
        }
        ServerRouteManager.RouteEntry entry = ServerRouteManager.existingEntry(serverData.ip);
        if (entry == null || !entry.autoSelect || entry.routes.isEmpty()) {
            return address; // no alternates configured: nothing to decide
        }
        String primary = ServerRouteManager.normalize(serverData.ip);
        String best = ServerRouteManager.bestRoute(serverData.ip);
        if (best == null) {
            // Measurements not ready yet (e.g. instant join right after opening the list)
            SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.literal("线路测速未就绪"), Component.literal("本次使用主线路连接"));
            return address;
        }
        ServerRouteManager.RouteStats stats = ServerRouteManager.statsFor(best);
        String detail = best + " (" + Math.round(stats.averageMs) + " ms, 丢包 "
                + Math.round(stats.lossPercent) + "%)";
        SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.literal(best.equals(primary) ? "线路自动选择：主线路" : "线路自动选择"),
                Component.literal(detail));
        if (best.equals(primary)) {
            return address;
        }
        LogUtils.getLogger().info("[CustomUI] Connecting via best route {} (primary {})",
                best, serverData.ip);
        return ServerAddress.parseString(best);
    }
}
