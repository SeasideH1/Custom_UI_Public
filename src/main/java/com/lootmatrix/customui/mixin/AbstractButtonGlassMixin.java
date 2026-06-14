package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.glass.GlassPanelRenderer;
import com.lootmatrix.customui.client.glass.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LabyMod-style frosted-glass skin for standard buttons on glass-themed
 * menus (see {@link GlassTheme}): replaces the vanilla sprite background
 * with a blurred-backdrop panel, keeping the vanilla label rendering
 * (scrolling text, colors, fade alpha) intact. Buttons with custom
 * renderWidget overrides (ImageButton, PlainTextButton, ...) are unaffected.
 */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonGlassMixin extends AbstractWidget {

    protected AbstractButtonGlassMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("HEAD"), cancellable = true)
    private void customui$glassSkin(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                    CallbackInfo ci) {
        if (!GlassTheme.buttonsActive()) {
            return;
        }
        if (!GlassPanelRenderer.drawPanel(graphics,
                getX(), getY(), getWidth(), getHeight(),
                this.alpha, isHoveredOrFocused(), this.active)) {
            return; // shaders unavailable: keep the vanilla widget skin
        }
        renderScrollingString(graphics, Minecraft.getInstance().font, 2,
                getFGColor() | (Mth.ceil(this.alpha * 255f) << 24));
        ci.cancel();
    }
}
