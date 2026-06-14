package com.lootmatrix.customui.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Helper class for batched rendering to reduce draw calls.
 * Accumulates multiple quads and submits them in a single draw call.
 * 
 * PERFORMANCE OPTIMIZATION:
 * - Reduces draw calls by batching similar render operations
 * - Minimizes GPU state changes
 * - Improves frame time by reducing CPU-GPU synchronization
 */
public class BatchedRenderHelper {
    
    private final BufferBuilder bufferBuilder;
    private boolean isBuilding = false;
    private int quadCount = 0;
    
    public BatchedRenderHelper() {
        this.bufferBuilder = Tesselator.getInstance().getBuilder();
    }
    
    /**
     * Begin batching colored quads.
     * Must call end() to submit the batch.
     */
    public void beginColoredQuads(Matrix4f matrix) {
        if (isBuilding) {
            throw new IllegalStateException("Already building a batch!");
        }
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        isBuilding = true;
        quadCount = 0;
    }
    
    /**
     * Add a colored quad to the batch.
     * 
     * @param matrix Transformation matrix
     * @param x Left position
     * @param y Top position
     * @param width Quad width
     * @param height Quad height
     * @param r Red component (0-1)
     * @param g Green component (0-1)
     * @param b Blue component (0-1)
     * @param a Alpha component (0-1)
     */
    public void addQuad(Matrix4f matrix, float x, float y, float width, float height,
                       float r, float g, float b, float a) {
        if (!isBuilding) {
            throw new IllegalStateException("Not building a batch! Call beginColoredQuads() first.");
        }
        
        float right = x + width;
        float bottom = y + height;
        
        bufferBuilder.vertex(matrix, x, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, y, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, x, y, 0).color(r, g, b, a).endVertex();
        
        quadCount++;
    }
    
    /**
     * Add a colored quad using ARGB color format.
     */
    public void addQuadARGB(Matrix4f matrix, float x, float y, float width, float height, int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        
        addQuad(matrix, x, y, width, height, r, g, b, a);
    }
    
    /**
     * End batching and submit all quads in a single draw call.
     * 
     * @return Number of quads rendered
     */
    public int end() {
        if (!isBuilding) {
            return 0;
        }
        
        int count = quadCount;
        
        if (quadCount > 0) {
            BufferUploader.drawWithShader(bufferBuilder.end());
        }
        
        RenderSystem.disableBlend();
        isBuilding = false;
        quadCount = 0;
        
        return count;
    }
    
    /**
     * Check if currently building a batch.
     */
    public boolean isBuilding() {
        return isBuilding;
    }
    
    /**
     * Get the number of quads added to the current batch.
     */
    public int getQuadCount() {
        return quadCount;
    }
}
