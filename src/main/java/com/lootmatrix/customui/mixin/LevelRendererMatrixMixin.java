package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.render.WorldToScreenUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 捕获本帧世界渲染矩阵（与 Superbwarfare LevelRendererMixin 相同的注入点）。
 *
 * renderLevel 中第 2 次（ordinal=1）applyModelViewMatrix 调用紧跟
 * {@code modelViewStack.mulPoseMatrix(poseStack.last().pose())}，且无条件执行：
 * 此刻 RenderSystem 的 model-view = 相机旋转，projection = 世界透视投影。
 * （ordinal=0 那次在批量 endBatch 前，model-view 仍是单位矩阵，不能用。）
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMatrixMixin {

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;applyModelViewMatrix()V",
                    ordinal = 1,
                    shift = At.Shift.AFTER))
    private void customui$captureMatrices(PoseStack poseStack, float partialTick,
                                          long finishNanoTime, boolean renderBlockOutline,
                                          Camera camera, GameRenderer gameRenderer,
                                          LightTexture lightTexture, Matrix4f projectionMatrix,
                                          CallbackInfo ci) {
        WorldToScreenUtil.captureMatrices();
    }
}
