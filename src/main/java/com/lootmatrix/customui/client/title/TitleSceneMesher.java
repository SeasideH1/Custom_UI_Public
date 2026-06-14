package com.lootmatrix.customui.client.title;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-off baking of the title scene into static per-RenderType VBOs.
 *
 * The whole structure is walked once with the vanilla
 * {@link BlockRenderDispatcher}, so models, AO, tint and random variants look
 * exactly like in-world chunks. After {@link #bake} the per-frame cost is one
 * {@code drawWithShader} per render type — zero allocation, zero rebuild.
 *
 * Translucent geometry is baked unsorted (no per-frame camera sort); scenes
 * should prefer cutout glass over large stained-glass volumes.
 */
@OnlyIn(Dist.CLIENT)
public final class TitleSceneMesher {

    /** Baked vertex buffer for one render type. */
    public static final class SceneLayer {
        public final RenderType renderType;
        public final VertexBuffer buffer;
        public final boolean translucent;

        SceneLayer(RenderType renderType, VertexBuffer buffer, boolean translucent) {
            this.renderType = renderType;
            this.buffer = buffer;
            this.translucent = translucent;
        }
    }

    private TitleSceneMesher() {}

    /**
     * Bakes every block of the scene. Must run on the render thread (GL
     * uploads). Returns the layers in draw order (opaque first, translucent
     * last); empty list when the scene contains nothing renderable.
     */
    public static List<SceneLayer> bake(SceneBlockGetter scene) {
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        PoseStack poseStack = new PoseStack();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = 0; y < scene.sizeY(); y++) {
            for (int z = 0; z < scene.sizeZ(); z++) {
                for (int x = 0; x < scene.sizeX(); x++) {
                    pos.set(x, y, z);
                    BlockState state = scene.getBlockState(pos);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    long seed = state.getSeed(pos);
                    random.setSeed(seed);
                    for (RenderType renderType : dispatcher.getBlockModel(state)
                            .getRenderTypes(state, random, ModelData.EMPTY)) {
                        BufferBuilder builder = builders.computeIfAbsent(renderType, rt -> {
                            BufferBuilder created = new BufferBuilder(262144);
                            created.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                            return created;
                        });
                        poseStack.pushPose();
                        poseStack.translate(x, y, z);
                        dispatcher.renderBatched(state, pos, scene, poseStack, builder,
                                true, random, ModelData.EMPTY, renderType);
                        poseStack.popPose();
                    }
                }
            }
        }

        List<SceneLayer> layers = new ArrayList<>(builders.size());
        List<SceneLayer> translucentLayers = new ArrayList<>(1);
        for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
            BufferBuilder.RenderedBuffer rendered = entry.getValue().end();
            if (rendered.drawState().vertexCount() == 0) {
                rendered.release();
                continue;
            }
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(rendered);
            VertexBuffer.unbind();
            boolean translucent = entry.getKey() == RenderType.translucent();
            SceneLayer layer = new SceneLayer(entry.getKey(), buffer, translucent);
            if (translucent) {
                translucentLayers.add(layer);
            } else {
                layers.add(layer);
            }
        }
        layers.addAll(translucentLayers);
        return layers;
    }

    public static void close(List<SceneLayer> layers) {
        for (int i = 0; i < layers.size(); i++) {
            layers.get(i).buffer.close();
        }
    }
}
