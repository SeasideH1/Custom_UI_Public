package com.lootmatrix.customui.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the protected widget fade alpha for the frosted-glass button skin. */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAlphaAccessor {

    @Accessor("alpha")
    float customui$getAlpha();
}
