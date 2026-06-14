package com.lootmatrix.customui.client.hud;

import com.lootmatrix.customui.hud.HudCondition;
import com.lootmatrix.customui.hud.HudElement;
import com.lootmatrix.customui.hud.HudInteraction;
import com.lootmatrix.customui.hud.HudTemplate;
import com.lootmatrix.customui.network.GuiInteractPacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive mouse-driven screen for screenType="gui" HUD templates.
 *
 * Renders through the shared batched overlay pipeline (flattened to 2D) and
 * adds: hover highlight on clickable elements, scoreboard-gated clicks with
 * success/fail feedback (sound / keyframe replay / recolor / open template),
 * close buttons, a template-declared close key (ESC always works) and an
 * optional world blur post effect while open.
 *
 * The screen works on a private copy of the template so click feedback
 * (recolors, animation rebases) never pollutes the shared client cache.
 */
@OnlyIn(Dist.CLIENT)
public class GuiTemplateScreen extends Screen {

    private final HudTemplate template;
    private final boolean notifyServer;
    /**
     * Wall-clock animation origin (millis). Driving GUI animation off real
     * time instead of game ticks keeps it perfectly smooth at any framerate
     * and even while the integrated server is paused (pauseGame=true).
     */
    private final long openMillis = net.minecraft.Util.getMillis();
    @Nullable private GuiBlurEffect blur;
    private boolean openSent = false;
    /** Per-element keyframe replay rebases (element -> time the replay started). */
    private final Map<HudElement, Float> animRebase = new IdentityHashMap<>();
    private final float[] boxScratch = new float[4];
    private final float[] rectScratch = new float[4];
    @Nullable private HudElement hoveredInteractive;

    private GuiTemplateScreen(HudTemplate template, boolean notifyServer) {
        super(Component.literal(template.id));
        this.template = template;
        this.notifyServer = notifyServer;
    }

    /**
     * Open a GUI template by id (from key press, command packet or another
     * template). {@code notifyServer} fires the OPEN interaction packet so the
     * template's open function runs and the session is tracked server-side.
     */
    public static void open(String templateId, boolean notifyServer) {
        HudTemplate source = HudClientTemplateCache.get(templateId);
        if (source == null || !source.isGui()) {
            com.mojang.logging.LogUtils.getLogger().warn(
                    "[CustomUI] GUI open ignored: template '{}' {}", templateId,
                    source == null ? "is not synced to this client" : "is not screenType=gui");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof GuiTemplateScreen current && current.template.id.equals(source.id)) {
                return; // already open
            }
            mc.setScreen(new GuiTemplateScreen(source.copy(), notifyServer));
        });
    }

    /** Server-initiated close (GUI_CLOSE packet). Empty id closes any GUI template screen. */
    public static void closeFromServer(String templateId) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof GuiTemplateScreen current
                    && (templateId.isEmpty() || current.template.id.equals(templateId))) {
                current.closeGui(false);
            }
        });
    }

    @Override
    public boolean isPauseScreen() {
        return template.pauseGame;
    }

    @Override
    protected void init() {
        if (template.blurBackground) {
            if (blur == null) {
                blur = new GuiBlurEffect();
            }
            blur.activate();
        }
        if (notifyServer && !openSent) {
            openSent = true;
            ModNetworkHandler.INSTANCE.sendToServer(GuiInteractPacket.open(template.id));
        }
    }

    @Override
    public void removed() {
        if (blur != null) {
            blur.close();
            blur = null;
        }
        HudElementRuntime.clearTemplate(template);
        super.removed();
    }

    /** Current animation time in tick units (50ms), loop-wrapped, frame-smooth. */
    private float animTime() {
        float t = (net.minecraft.Util.getMillis() - openMillis) / 50f;
        if (template.loop && !template.isPersistent()) {
            int lifetime = template.effectiveLifetime();
            if (lifetime > 0 && t >= lifetime) t %= lifetime;
        }
        return t;
    }

    // ==================== Render ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Wall-clock time: smooth at any framerate, immune to tick stutter/pause
        HudTemplateOverlayRenderer.setTimeOverrides(animRebase.isEmpty() ? null : animRebase);
        HudTemplateOverlayRenderer.renderTemplatePreview(graphics, template, animTime(), width, height);
        HudTemplateOverlayRenderer.setTimeOverrides(null);

        hoveredInteractive = findInteractiveAt(mouseX, mouseY);
        if (hoveredInteractive != null) {
            graphics.fill(Math.round(boxScratch[0]), Math.round(boxScratch[1]),
                    Math.round(boxScratch[0] + boxScratch[2]), Math.round(boxScratch[1] + boxScratch[3]),
                    0x28FFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ==================== Hit testing ====================

    private static boolean isInteractive(HudElement e) {
        return e.closeButton || e.onClick != null || e.onClickFail != null;
    }

    /**
     * Topmost interactive element under the cursor; the element's resolved box
     * is left in {@code boxScratch}. Recurses into GROUP children (group-local
     * rect space, rotation ignored inside groups).
     */
    @Nullable
    private HudElement findInteractiveAt(double mouseX, double mouseY) {
        return findInList(template.renderOrder(), mouseX, mouseY, 0f, 0f, width, height, 0);
    }

    @Nullable
    private HudElement findInList(List<HudElement> order, double mouseX, double mouseY,
                                  float rectX, float rectY, float rectW, float rectH, int depth) {
        for (int i = order.size() - 1; i >= 0; i--) {
            HudElement e = order.get(i);
            if (!e.visible) continue;
            if (e.type == HudElement.Type.GROUP && depth < HudTemplate.MAX_NESTING_DEPTH) {
                HudTemplateOverlayRenderer.resolveLocalBoxInRect(
                        e, localTime(e), rectX, rectY, rectW, rectH, rectScratch);
                float gx = rectScratch[0], gy = rectScratch[1], gw = rectScratch[2], gh = rectScratch[3];
                HudElement hit = findInList(e.childRenderOrder(), mouseX, mouseY, gx, gy, gw, gh, depth + 1);
                if (hit != null) return hit;
            }
            if (!isInteractive(e)) continue;
            HudTemplateOverlayRenderer.resolveLocalBoxInRect(
                    e, localTime(e), rectX, rectY, rectW, rectH, boxScratch);
            if (mouseX >= boxScratch[0] && mouseX <= boxScratch[0] + boxScratch[2]
                    && mouseY >= boxScratch[1] && mouseY <= boxScratch[1] + boxScratch[3]) {
                return e;
            }
        }
        return null;
    }

    private float localTime(HudElement e) {
        float t = animTime();
        Float rebase = animRebase.get(e);
        return rebase != null ? Math.max(0f, t - rebase) : t;
    }

    // ==================== Interaction ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            HudElement target = findInteractiveAt(mouseX, mouseY);
            if (target != null) {
                handleClick(target);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleClick(HudElement e) {
        if (e.closeButton) {
            // Optional click feedback still applies before closing
            if (e.onClick != null) applyFeedback(e.onClick);
            closeGui(true);
            return;
        }
        HudCondition condition = HudCondition.parse(e.condition);
        boolean pass = condition == null
                || condition.test(HudScoreboardClientCache.value(condition.binding.key()));
        HudInteraction interaction = pass ? e.onClick : e.onClickFail;
        if (interaction == null) return;

        applyFeedback(interaction);
        if (interaction.function != null && !e.id.isEmpty()) {
            // Server re-validates the condition before running the function
            ModNetworkHandler.INSTANCE.sendToServer(GuiInteractPacket.click(template.id, e.id, pass));
        }
        if (interaction.close) {
            closeGui(true);
        }
    }

    /** Client-side feedback: sound, keyframe replay, recolors, opening templates. */
    private void applyFeedback(HudInteraction interaction) {
        if (interaction.sound != null) {
            ResourceLocation rl = ResourceLocation.tryParse(interaction.sound);
            if (rl != null && minecraft != null) {
                minecraft.getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(rl), 1.0f));
            }
        }
        if (interaction.anim != null) {
            HudElement target = findById(template.elements, interaction.anim, 0);
            if (target != null && !target.keyframes.isEmpty()) {
                animRebase.put(target, animTime());
            }
        }
        for (int i = 0; i < interaction.setColor.size(); i++) {
            HudInteraction.SetColor sc = interaction.setColor.get(i);
            HudElement target = findById(template.elements, sc.target, 0);
            if (target == null) continue;
            if (sc.fill != null) target.fillColor = sc.fill;
            if (sc.textColor != null) target.textColor = sc.textColor;
            HudElementRuntime.invalidate(target);
        }
        if (interaction.openTemplate != null) {
            HudTemplate next = HudClientTemplateCache.get(interaction.openTemplate);
            if (next != null) {
                if (next.isGui()) {
                    closeGui(true);
                    open(next.id, true);
                } else {
                    HudPlaybackManager.play(next.id, 0);
                }
            }
        }
    }

    @Nullable
    private static HudElement findById(List<HudElement> list, String id, int depth) {
        if (id.isEmpty() || depth > HudTemplate.MAX_NESTING_DEPTH) return null;
        for (int i = 0; i < list.size(); i++) {
            HudElement e = list.get(i);
            if (id.equals(e.id)) return e;
            if (!e.children.isEmpty()) {
                HudElement found = findById(e.children, id, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ==================== Close ====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE
                || HudKeyBindings.matches(template.closeKey, keyCode, scanCode)
                || HudKeyBindings.matches(template.openKey, keyCode, scanCode)) {
            closeGui(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Close the screen; when player-initiated, the server runs the close function. */
    private void closeGui(boolean notify) {
        if (notify) {
            ModNetworkHandler.INSTANCE.sendToServer(GuiInteractPacket.close(template.id));
        }
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
