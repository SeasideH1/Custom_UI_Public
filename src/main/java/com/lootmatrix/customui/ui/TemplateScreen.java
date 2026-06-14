package com.lootmatrix.customui.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lootmatrix.customui.client.render.RenderResourceCache;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.network.UIWidgetActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Client-side screen that renders a UI template from the server.
 * Uses a reference canvas (default 480×270) with uniform scaling.
 */
public class TemplateScreen extends Screen {

    private final UITemplate template;
    private final ResourceLocation backgroundTexture;
    private float openProgress = 0f;
    private boolean closing = false;
    private float closeProgress = 0f;

    public TemplateScreen(UITemplate template, Map<String, String> resolvedVars) {
        super(Component.literal(template.title));
        this.template = template;
        this.template.resolvedVars = resolvedVars;
        this.backgroundTexture = RenderResourceCache.get(template.background);
    }

    public static void openFromPacket(String templateJson, Map<String, String> vars) {
        try {
            JsonObject json = new GsonBuilder().create().fromJson(templateJson, JsonObject.class);
            UITemplate template = UITemplate.fromJson("synced", json);
            template.resolvedVars = vars;
            Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().setScreen(new TemplateScreen(template, vars)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void closeFromPacket() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof TemplateScreen ts) {
                ts.startClose();
            }
        });
    }

    public static void openEditorFromPacket(String templateJson) {
        try {
            JsonObject json = new GsonBuilder().create().fromJson(templateJson, JsonObject.class);
            UITemplate template = UITemplate.fromJson("editor", json);
            Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().setScreen(new TemplateEditor(template)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return template.pauseGame;
    }

    @Override
    protected void init() {
        openProgress = 0f;
    }

    @Override
    public void tick() {
        if (!closing) {
            openProgress = Math.min(1f, openProgress + 1f / Math.max(1, template.openAnimTicks));
        } else {
            closeProgress = Math.min(1f, closeProgress + 1f / Math.max(1, template.closeAnimTicks));
            if (closeProgress >= 1f) {
                notifyServerClose();
                this.minecraft.setScreen(null);
            }
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        float anim = closing ? 1f - easeOut(closeProgress) : easeOut(openProgress);
        float scale = computeScale();

        // Background overlay
        int alpha = (int) (((template.backgroundColor >> 24) & 0xFF) * anim);
        int bgColor = (alpha << 24) | (template.backgroundColor & 0x00FFFFFF);
        gfx.fill(0, 0, this.width, this.height, bgColor);

        // Canvas transform with scale-in animation
        float animScale = 0.9f + 0.1f * anim;
        float totalScale = scale * animScale;
        float cx = this.width / 2f;
        float cy = this.height / 2f;

        gfx.pose().pushPose();
        gfx.pose().translate(cx, cy, 0);
        gfx.pose().scale(totalScale, totalScale, 1f);
        gfx.pose().translate(-template.canvasWidth / 2f, -template.canvasHeight / 2f, 0);

        // Convert mouse to canvas coords
        float canvasMouseX = ((mouseX - cx) / totalScale) + template.canvasWidth / 2f;
        float canvasMouseY = ((mouseY - cy) / totalScale) + template.canvasHeight / 2f;

        // Background texture
        if (backgroundTexture != null) {
            gfx.blit(backgroundTexture, 0, 0, template.canvasWidth, template.canvasHeight,
                    0, 0, template.canvasWidth, template.canvasHeight,
                    template.canvasWidth, template.canvasHeight);
        }

        // Render widgets
        for (UIWidget widget : template.widgets) {
            if (!widget.visible) continue;
            WidgetRenderer.render(gfx, widget, canvasMouseX, canvasMouseY, partialTick, template, this.font);
        }

        gfx.pose().popPose();

        // Tooltips (in screen space)
        for (UIWidget widget : template.widgets) {
            if (!widget.visible || widget.tooltip == null) continue;
            if (isHovered(widget, canvasMouseX, canvasMouseY)) {
                gfx.renderTooltip(this.font, Component.literal(widget.tooltip), mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        float anim = closing ? 0 : easeOut(openProgress);
        float totalScale = computeScale() * (0.9f + 0.1f * anim);
        float cx = this.width / 2f;
        float cy = this.height / 2f;
        float canvasX = (float) ((mouseX - cx) / totalScale) + template.canvasWidth / 2f;
        float canvasY = (float) ((mouseY - cy) / totalScale) + template.canvasHeight / 2f;

        // Iterate reverse for top-most widget
        for (int i = template.widgets.size() - 1; i >= 0; i--) {
            UIWidget widget = template.widgets.get(i);
            if (!widget.visible) continue;
            if (isHovered(widget, canvasX, canvasY)) {
                handleWidgetClick(widget);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleWidgetClick(UIWidget widget) {
        switch (widget.type) {
            case BUTTON:
                if (widget.onClick != null) {
                    sendAction(widget.id, UIWidgetActionPacket.ActionType.CLICK, "");
                    if (widget.onClick.closeOnExecute) startClose();
                }
                break;
            case TOGGLE:
                widget.toggleState = !widget.toggleState;
                sendAction(widget.id, UIWidgetActionPacket.ActionType.TOGGLE,
                        String.valueOf(widget.toggleState));
                break;
            default:
                break;
        }
    }

    private void sendAction(String widgetId, UIWidgetActionPacket.ActionType type, String payload) {
        ModNetworkHandler.INSTANCE.sendToServer(
                new UIWidgetActionPacket(template.id, widgetId, type, payload));
    }

    private void startClose() {
        closing = true;
        closeProgress = 0f;
    }

    private void notifyServerClose() {
        ModNetworkHandler.INSTANCE.sendToServer(
                new UIWidgetActionPacket(template.id, "", UIWidgetActionPacket.ActionType.CLOSE, ""));
    }

    @Override
    public void onClose() {
        notifyServerClose();
        super.onClose();
    }

    private float computeScale() {
        return Math.min(
                (float) this.width / template.canvasWidth,
                (float) this.height / template.canvasHeight);
    }

    private boolean isHovered(UIWidget widget, float cx, float cy) {
        float wx = widget.x - widget.w * widget.originX;
        float wy = widget.y - widget.h * widget.originY;
        return cx >= wx && cx <= wx + widget.w && cy >= wy && cy <= wy + widget.h;
    }

    private static float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }
}
