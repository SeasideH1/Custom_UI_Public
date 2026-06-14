package com.lootmatrix.customui.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import net.minecraftforge.fml.loading.FMLConfig;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With the FML early window disabled ({@code earlyWindowControl=false}) the
 * vanilla window opens blank (white on Windows) and stays that way until the
 * loading overlay's first frame, because nothing renders during client mod
 * loader startup. Paint one themed frame the moment the window exists so it
 * never flashes white — the color matches the CustomLoadingOverlay backdrop
 * that takes over right after.
 */
@Mixin(Window.class)
public abstract class WindowFirstFrameMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void customui$paintFirstFrame(CallbackInfo ci) {
        if (FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL)) {
            return; // FML early window owns the surface; don't flash over it
        }
        // 0x10141A = CustomLoadingOverlay.BACKGROUND_RGB
        GlStateManager._clearColor(0x10 / 255f, 0x14 / 255f, 0x1A / 255f, 1f);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, false);
        GLFW.glfwSwapBuffers(((Window) (Object) this).getWindow());
    }
}
