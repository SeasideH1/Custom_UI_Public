package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.glass.GlassPanelRenderer;
import com.lootmatrix.customui.client.glass.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frosted-glass skin for value sliders (FOV, volume, ... in the options
 * menus): glass track sampled in place plus a crisp solid handle, keeping the
 * vanilla scrolling label. Applies on the same screens as the glass buttons
 * and falls back to the vanilla sprites when the pipeline is unavailable.
 */
@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonGlassMixin extends AbstractWidget {

    protected AbstractSliderButtonGlassMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("HEAD"), cancellable = true)
    private void customui$glassSlider(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                      CallbackInfo ci) {
        if (!GlassTheme.buttonsActive()) {
            return;
        }
        if (!GlassPanelRenderer.drawPanel(graphics, getX(), getY(), getWidth(), getHeight(),
                this.alpha, isHoveredOrFocused(), this.active)) {
            return; // pipeline unavailable: keep the vanilla slider skin
        }
        // Solid handle so the grab point stays crisp on top of the glass track
        double value = ((AbstractSliderButtonValueAccessor) this).customui$getValue();
        int handleX = getX() + (int) (value * (getWidth() - 8));
        int handleColor = isHoveredOrFocused() ? 0xE6FFFFFF : 0xB4FFFFFF;
        int handleAlpha = (int) (((handleColor >>> 24) & 0xFF) * this.alpha);
        graphics.fill(handleX + 1, getY() + 1, handleX + 7, getY() + getHeight() - 1,
                (handleAlpha << 24) | (handleColor & 0x00FFFFFF));

        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        renderScrollingString(graphics, Minecraft.getInstance().font, 2,
                textColor | (Mth.ceil(this.alpha * 255f) << 24));
        ci.cancel();
    }
}
