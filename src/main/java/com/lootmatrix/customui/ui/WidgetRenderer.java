package com.lootmatrix.customui.ui;

import com.lootmatrix.customui.client.render.RenderResourceCache;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Stateless renderer for individual UI widgets on the reference canvas.
 */
public class WidgetRenderer {

    public static void render(GuiGraphics gfx, UIWidget widget, float mouseX, float mouseY,
                              float partialTick, UITemplate template, Font font) {
        float wx = widget.x - widget.w * widget.originX;
        float wy = widget.y - widget.h * widget.originY;
        boolean hovered = mouseX >= wx && mouseX <= wx + widget.w && mouseY >= wy && mouseY <= wy + widget.h;

        switch (widget.type) {
            case LABEL:    renderLabel(gfx, widget, wx, wy, template, font); break;
            case IMAGE:    renderImage(gfx, widget, wx, wy); break;
            case BUTTON:   renderButton(gfx, widget, wx, wy, hovered, template, font); break;
            case PROGRESS: renderProgress(gfx, widget, wx, wy, template); break;
            case TOGGLE:   renderToggle(gfx, widget, wx, wy, hovered, font); break;
            case INPUT:    renderInput(gfx, widget, wx, wy, hovered, font); break;
            case ITEM_ICON: renderItemIcon(gfx, widget, wx, wy, font); break;
            case PANEL:    renderPanel(gfx, widget, wx, wy, mouseX, mouseY, partialTick, template, font); break;
            case DIVIDER:  renderDivider(gfx, widget, wx, wy); break;
        }
    }

    // ==================== Label ====================

    private static void renderLabel(GuiGraphics gfx, UIWidget w, float x, float y,
                                    UITemplate template, Font font) {
        String text = template.resolveText(w.text);
        if (text == null) return;

        String[] lines = text.split("\n");
        float lineHeight = font.lineHeight + 1;
        float fScale = w.fontSize / 9f;

        gfx.pose().pushPose();
        gfx.pose().scale(fScale, fScale, 1f);

        float lineY = y;
        for (String line : lines) {
            float drawX = x / fScale;
            float drawY = lineY / fScale;
            int textWidth = font.width(line);

            if ("center".equals(w.textAlign)) {
                drawX = (x + w.w / 2f) / fScale - textWidth / 2f;
            } else if ("right".equals(w.textAlign)) {
                drawX = (x + w.w) / fScale - textWidth;
            }

            gfx.drawString(font, line, (int) drawX, (int) drawY, w.textColor, w.textShadow);
            lineY += lineHeight * fScale;
        }
        gfx.pose().popPose();
    }

    // ==================== Image ====================

    private static void renderImage(GuiGraphics gfx, UIWidget w, float x, float y) {
        if (w.texture == null) return;
        ResourceLocation tex = RenderResourceCache.get(w.texture);
        if (tex == null) return;
        RenderSystem.enableBlend();
        gfx.blit(tex, (int) x, (int) y, (int) w.w, (int) w.h,
                w.u0, w.v0, (int) (w.u1 - w.u0), (int) (w.v1 - w.v0), w.texW, w.texH);
    }

    // ==================== Button ====================

    private static void renderButton(GuiGraphics gfx, UIWidget w, float x, float y,
                                     boolean hovered, UITemplate template, Font font) {
        int color = hovered ? w.hoverColor : w.normalColor;
        gfx.fill((int) x, (int) y, (int) (x + w.w), (int) (y + w.h), color);

        // Texture
        if (w.texture != null) {
            ResourceLocation tex = RenderResourceCache.get(w.texture);
            if (tex != null) {
                float pad = 4;
                float texH = w.h - pad * 2;
                float texW = texH; // square by default
                if (w.label != null) {
                    texH = w.h * 0.55f;
                }
                float texX = x + (w.w - texW) / 2f;
                float texY = y + pad;
                RenderSystem.enableBlend();
                gfx.blit(tex, (int) texX, (int) texY, (int) texW, (int) texH,
                        w.u0, w.v0, (int) (w.u1 - w.u0), (int) (w.v1 - w.v0), w.texW, w.texH);
            }
        }

        // Label
        if (w.label != null) {
            String text = template.resolveText(w.label);
            String[] lines = text.split("\n");
            float labelStartY = w.texture != null
                    ? y + w.h * 0.58f
                    : y + (w.h - font.lineHeight * lines.length) / 2f;

            for (String line : lines) {
                int tw = font.width(line);
                float drawX = x + (w.w - tw) / 2f;
                gfx.drawString(font, line, (int) drawX, (int) labelStartY, 0xFFFFFFFF);
                labelStartY += font.lineHeight + 1;
            }
        }
    }

    // ==================== Progress ====================

    private static void renderProgress(GuiGraphics gfx, UIWidget w, float x, float y, UITemplate template) {
        float val = w.value;
        if (w.variable != null && template.resolvedVars.containsKey(w.variable)) {
            try { val = Float.parseFloat(template.resolvedVars.get(w.variable)); }
            catch (NumberFormatException ignored) {}
        }
        float ratio = w.max > 0 ? Math.min(1f, val / w.max) : 0;

        gfx.fill((int) x, (int) y, (int) (x + w.w), (int) (y + w.h), w.bgColor);
        int barW = (int) (w.w * ratio);
        if (barW > 0) {
            gfx.fill((int) x, (int) y, (int) x + barW, (int) (y + w.h), w.barColor);
        }
    }

    // ==================== Toggle ====================

    private static void renderToggle(GuiGraphics gfx, UIWidget w, float x, float y,
                                     boolean hovered, Font font) {
        int bgColor = w.toggleState ? 0xFF44AA44 : 0xFF666666;
        if (hovered) bgColor = w.toggleState ? 0xFF55CC55 : 0xFF888888;
        gfx.fill((int) x, (int) y, (int) (x + w.w), (int) (y + w.h), bgColor);

        // Indicator knob
        float knobW = w.h - 4;
        float knobX = w.toggleState ? x + w.w - knobW - 2 : x + 2;
        gfx.fill((int) knobX, (int) (y + 2), (int) (knobX + knobW), (int) (y + w.h - 2), 0xFFFFFFFF);

        // Label to the right
        if (w.label != null) {
            gfx.drawString(font, w.label, (int) (x + w.w + 4),
                    (int) (y + (w.h - font.lineHeight) / 2f), 0xFFFFFFFF);
        }
    }

    // ==================== Input ====================

    private static void renderInput(GuiGraphics gfx, UIWidget w, float x, float y,
                                    boolean hovered, Font font) {
        int border = hovered ? 0xFFAAAAAA : 0xFF666666;
        gfx.fill((int) x, (int) y, (int) (x + w.w), (int) (y + w.h), 0xFF111111);
        // Border lines
        gfx.fill((int) x, (int) y, (int) (x + w.w), (int) y + 1, border);
        gfx.fill((int) x, (int) (y + w.h - 1), (int) (x + w.w), (int) (y + w.h), border);
        gfx.fill((int) x, (int) y, (int) x + 1, (int) (y + w.h), border);
        gfx.fill((int) (x + w.w - 1), (int) y, (int) (x + w.w), (int) (y + w.h), border);

        if (w.placeholder != null) {
            gfx.drawString(font, w.placeholder, (int) (x + 4),
                    (int) (y + (w.h - font.lineHeight) / 2f), 0xFF888888, false);
        }
    }

    // ==================== Item Icon ====================

    private static void renderItemIcon(GuiGraphics gfx, UIWidget w, float x, float y, Font font) {
        // Placeholder rectangle for item display
        gfx.fill((int) x, (int) y, (int) (x + w.w), (int) (y + w.h), 0x40FFFFFF);
        if (w.itemId != null) {
            gfx.drawString(font, w.itemId, (int) (x + 2), (int) (y + 2), 0xFFCCCCCC, false);
        }
    }

    // ==================== Panel ====================

    private static void renderPanel(GuiGraphics gfx, UIWidget w, float x, float y,
                                    float mouseX, float mouseY, float partialTick,
                                    UITemplate template, Font font) {
        gfx.fill((int) x, (int) y, (int) (x + w.w), (int) (y + w.h), 0x40000000);
        if (w.children == null) return;

        gfx.pose().pushPose();
        gfx.pose().translate(x, y, 0);
        for (UIWidget child : w.children) {
            if (!child.visible) continue;
            render(gfx, child, mouseX - x, mouseY - y, partialTick, template, font);
        }
        gfx.pose().popPose();
    }

    // ==================== Divider ====================

    private static void renderDivider(GuiGraphics gfx, UIWidget w, float x, float y) {
        gfx.fill((int) x, (int) y, (int) (x + w.w), (int) (y + w.dividerThickness), w.dividerColor);
    }
}
