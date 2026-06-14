package com.lootmatrix.customui.mixin;

import com.mojang.blaze3d.platform.GlDebug;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to filter OpenGL debug errors caused by TaCZ/GeckoLib rendering transitions.
 * Suppresses depth-related GL errors and framebuffer state warnings.
 */
@Mixin(value = GlDebug.class, remap = false)
public class GlDebugMixin {

    @Inject(method = "printDebugLog", at = @At("HEAD"), cancellable = true)
    private static void onPrintDebugLog(int source, int type, int id, int severity,
                                         int messageLength, long message, long userParam,
                                         CallbackInfo ci) {
        // Only suppress specific known harmless GL errors from TaCZ/GeckoLib rendering.
        // Do NOT suppress all GL_DEBUG_TYPE_ERROR — that masks real bugs.
        if (type == GL43.GL_DEBUG_TYPE_ERROR && message != 0 && messageLength > 0) {
            try {
                String msg = MemoryUtil.memUTF8(message, messageLength);
                if (msg != null) {
                    // TaCZ/GeckoLib triggers "Depth formats do not match" every frame
                    // during glBlitFramebuffer calls in their entity rendering pipeline.
                    // This is a known harmless driver warning (id=1282 GL_INVALID_OPERATION).
                    if (msg.contains("Depth formats do not match")) {
                        ci.cancel();
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}
