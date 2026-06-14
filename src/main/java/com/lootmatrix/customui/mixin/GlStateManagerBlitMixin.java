package com.lootmatrix.customui.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes "Depth formats do not match" GL_INVALID_OPERATION errors caused by
 * TaCZ/GeckoLib calling glBlitFramebuffer when source and destination FBOs
 * have different depth attachment formats.
 *
 * Instead of merely suppressing the debug message (GlDebugMixin), this mixin
 * prevents the invalid operation entirely by checking whether the read and draw
 * framebuffers have matching depth formats before the blit call proceeds.
 * If they don't match and the mask includes GL_DEPTH_BUFFER_BIT, the depth bit
 * is stripped so only color (and stencil if requested) are blitted.
 */
@Mixin(value = GlStateManager.class, remap = false)
public class GlStateManagerBlitMixin {

    @Inject(method = "_glBlitFrameBuffer", at = @At("HEAD"), cancellable = true)
    private static void fixDepthFormatMismatch(int srcX0, int srcY0, int srcX1, int srcY1,
                                                int dstX0, int dstY0, int dstX1, int dstY1,
                                                int mask, int filter,
                                                CallbackInfo ci) {
        if ((mask & GL30.GL_DEPTH_BUFFER_BIT) == 0) {
            return; // no depth blit requested, nothing to fix
        }

        int readFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        if (readFbo == 0 || drawFbo == 0) {
            return; // default framebuffer involved, skip check
        }

        // Query the depth attachment type of both FBOs
        int readDepthType = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int drawDepthType = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);

        // If either side has no depth attachment, strip the depth bit
        if (readDepthType == GL30.GL_NONE || drawDepthType == GL30.GL_NONE) {
            int fixedMask = mask & ~GL30.GL_DEPTH_BUFFER_BIT;
            if (fixedMask == 0) {
                ci.cancel(); // nothing left to blit
                return;
            }
            GL30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1,
                    dstX0, dstY0, dstX1, dstY1, fixedMask, filter);
            ci.cancel();
            return;
        }

        // Both have depth attachments — check if internal formats match
        int readDepthName = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int drawDepthName = GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

        int readFormat = getTextureInternalFormat(readDepthType, readDepthName);
        int drawFormat = getTextureInternalFormat(drawDepthType, drawDepthName);

        if (readFormat != 0 && drawFormat != 0 && readFormat != drawFormat) {
            // Depth formats differ — strip the depth bit to avoid GL_INVALID_OPERATION
            int fixedMask = mask & ~GL30.GL_DEPTH_BUFFER_BIT;
            if (fixedMask == 0) {
                ci.cancel();
                return;
            }
            GL30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1,
                    dstX0, dstY0, dstX1, dstY1, fixedMask, filter);
            ci.cancel();
        }
    }

    private static int getTextureInternalFormat(int objectType, int objectName) {
        if (objectName == 0) return 0;
        if (objectType == GL30.GL_TEXTURE) {
            // Query the internal format of the texture
            int prev = GL30.glGetInteger(GL30.GL_TEXTURE_BINDING_2D);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, objectName);
            int format = GL30.glGetTexLevelParameteri(GL30.GL_TEXTURE_2D, 0,
                    GL30.GL_TEXTURE_INTERNAL_FORMAT);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, prev);
            return format;
        } else if (objectType == GL30.GL_RENDERBUFFER) {
            int prev = GL30.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, objectName);
            int format = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER,
                    GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prev);
            return format;
        }
        return 0;
    }
}
