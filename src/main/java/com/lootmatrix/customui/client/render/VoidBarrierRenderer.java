package com.lootmatrix.customui.client.render;

import com.lootmatrix.customui.block.VoidBarrierBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import org.jetbrains.annotations.NotNull;

/**
 * VoidBarrier 方块实体渲染器 - 空实现
 * 实际渲染由 VoidBarrierWorldRenderer 在世界渲染早期阶段处理
 */
public class VoidBarrierRenderer implements BlockEntityRenderer<VoidBarrierBlockEntity> {

    public VoidBarrierRenderer(BlockEntityRendererProvider.Context ignored) {
    }

    @Override
    public void render(
            @NotNull VoidBarrierBlockEntity be,
            float partialTick,
            @NotNull PoseStack poseStack,
            @NotNull MultiBufferSource buffer,
            int light,
            int overlay
    ) {
        // 不在这里渲染 - 由 VoidBarrierWorldRenderer 处理
    }

    @Override
    public boolean shouldRenderOffScreen(@NotNull VoidBarrierBlockEntity be) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}






