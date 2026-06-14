package com.lootmatrix.customui.client.route;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Route manager UI opened from the vanilla Edit Server screen: maintains the
 * alternate address list of one server, shows live latency / loss / jitter
 * per route (auto-refreshing while probes finish) and toggles automatic
 * best-route selection.
 */
@OnlyIn(Dist.CLIENT)
public class ServerRouteScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 48;

    private final Screen parent;
    private final String serverName;
    private final String primaryAddress;
    private final ServerRouteManager.RouteEntry entry;

    @Nullable private EditBox addressInput;
    private final List<Button> deleteButtons = new ArrayList<>();

    public ServerRouteScreen(Screen parent, String serverName, String primaryAddress) {
        super(Component.literal("线路管理 - " + serverName));
        this.parent = parent;
        this.serverName = serverName;
        this.primaryAddress = ServerRouteManager.normalize(primaryAddress);
        this.entry = ServerRouteManager.entryFor(primaryAddress);
    }

    @Override
    protected void init() {
        deleteButtons.clear();
        int centerX = width / 2;

        // Per-route delete buttons (primary row has none)
        List<String> candidates = ServerRouteManager.candidates(primaryAddress);
        for (int i = 1; i < candidates.size(); i++) {
            final String route = candidates.get(i);
            Button delete = Button.builder(Component.literal("删除"), button -> {
                        entry.routes.remove(route);
                        ServerRouteManager.save();
                        rebuildWidgets();
                    })
                    .bounds(centerX + 130, LIST_TOP + i * ROW_HEIGHT - 2, 40, 18)
                    .build();
            deleteButtons.add(delete);
            addRenderableWidget(delete);
        }

        int inputY = LIST_TOP + candidates.size() * ROW_HEIGHT + 8;
        addressInput = new EditBox(font, centerX - 170, inputY, 260, 18,
                Component.literal("新线路地址"));
        addressInput.setMaxLength(128);
        addressInput.setHint(Component.literal("ip[:端口] — 例如 cn.example.com:25565"));
        addWidget(addressInput);

        addRenderableWidget(Button.builder(Component.literal("添加线路"), button -> addRoute())
                .bounds(centerX + 96, inputY - 1, 74, 20).build());

        int bottomY = inputY + 30;
        addRenderableWidget(Button.builder(
                        Component.literal("自动选择: " + (entry.autoSelect ? "开" : "关")), button -> {
                            entry.autoSelect = !entry.autoSelect;
                            ServerRouteManager.save();
                            rebuildWidgets();
                        })
                .bounds(centerX - 170, bottomY, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("立即测速"), button ->
                        ServerRouteManager.probeServer(primaryAddress))
                .bounds(centerX - 54, bottomY, 104, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), button -> onClose())
                .bounds(centerX + 56, bottomY, 114, 20).build());

        ServerRouteManager.probeServer(primaryAddress);
    }

    private void addRoute() {
        if (addressInput == null) return;
        String address = ServerRouteManager.normalize(addressInput.getValue());
        if (address.isEmpty() || address.equals(primaryAddress) || entry.routes.contains(address)) {
            return;
        }
        entry.routes.add(address);
        ServerRouteManager.save();
        ServerRouteManager.probeRoute(address);
        addressInput.setValue("");
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (addressInput != null && addressInput.isFocused()
                && (keyCode == 257 || keyCode == 335)) { // Enter
            addRoute();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        graphics.drawCenteredString(font, title, centerX, 18, 0xFFFFFF);
        graphics.drawCenteredString(font,
                "加入服务器时自动套用最优线路（开启自动选择即可，无需打开本界面）",
                centerX, 30, 0x8FA1AC);

        String best = ServerRouteManager.bestRoute(primaryAddress);
        List<String> candidates = ServerRouteManager.candidates(primaryAddress);
        for (int i = 0; i < candidates.size(); i++) {
            String route = candidates.get(i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            String label = (i == 0 ? "主线路  " : "线路 " + i + "  ") + route;
            int labelColor = route.equals(best) ? 0x7CFC9A : 0xE6EDF3;
            graphics.drawString(font, label, centerX - 170, y, labelColor);
            graphics.drawString(font, statsLine(route), centerX - 170, y + 9, 0x8FA1AC);
            if (route.equals(best)) {
                graphics.drawString(font, "◀ 最优", centerX + 174 - font.width("◀ 最优"), y, 0x7CFC9A);
            }
        }
        if (addressInput != null) {
            addressInput.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private static String statsLine(String route) {
        ServerRouteManager.RouteStats stats = ServerRouteManager.statsFor(route);
        if (stats.probing) {
            return "测速中…（协议层 ping，5 次采样）";
        }
        if (!stats.hasResult()) {
            return "未测速";
        }
        if (stats.lossPercent >= 99.9f) {
            return "不可达";
        }
        return formatMs(stats.averageMs) + " ms  丢包 " + Math.round(stats.lossPercent)
                + "%  波动 " + formatMs(stats.jitterMs) + " ms";
    }

    /** One decimal below 100 ms so LAN/proxy routes never show a bogus "0 ms". */
    private static String formatMs(float value) {
        if (value >= 100f) {
            return Integer.toString(Math.round(value));
        }
        int scaled = Math.round(value * 10f);
        return (scaled / 10) + "." + Math.abs(scaled % 10);
    }

    @Override
    public void onClose() {
        ServerRouteManager.save();
        Minecraft.getInstance().setScreen(parent);
    }
}
