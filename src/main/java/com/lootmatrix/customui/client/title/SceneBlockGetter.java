package com.lootmatrix.customui.client.title;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * Standalone block container for the baked title scene.
 *
 * Implements just enough of {@link BlockAndTintGetter} for
 * {@code BlockRenderDispatcher.renderBatched} to produce correct geometry,
 * ambient occlusion and light coordinates without a real level:
 * <ul>
 *   <li>block/sky light is precomputed once: a flood fill from emissive
 *       blocks plus top-down column skylight with one spread pass</li>
 *   <li>biome tints resolve to fixed plains-like constants (no Biome instance
 *       exists outside a registry-backed level)</li>
 * </ul>
 * All light queries outside the structure bounds return full skylight so the
 * scene edges do not render pitch black.
 */
@OnlyIn(Dist.CLIENT)
public final class SceneBlockGetter implements BlockAndTintGetter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int GRASS_TINT = 0x91BD59;
    private static final int FOLIAGE_TINT = 0x77AB2F;
    private static final int WATER_TINT = 0x3F76E4;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockState[] states;
    private final byte[] blockLight;
    private final byte[] skyLight;
    private final int ambientSkyLight;
    private final int ambientBlockLight;

    public SceneBlockGetter(int sizeX, int sizeY, int sizeZ, int ambientSkyLight, int ambientBlockLight) {
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);
        int volume = this.sizeX * this.sizeY * this.sizeZ;
        this.states = new BlockState[volume];
        this.blockLight = new byte[volume];
        this.skyLight = new byte[volume];
        this.ambientSkyLight = ambientSkyLight;
        this.ambientBlockLight = ambientBlockLight;
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }

    public void setBlock(int x, int y, int z, BlockState state) {
        if (inBounds(x, y, z) && state != null && !state.isAir()) {
            states[index(x, y, z)] = state;
        }
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
    }

    private int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    // ==================== Lighting precompute ====================

    /**
     * Once-off light bake: emissive flood fill (block light) + top-down column
     * skylight with a single BFS spread so light leaks softly into overhangs.
     */
    public void computeLighting() {
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

        // --- Block light: seed from emissive blocks, BFS with -1 per step ---
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockState state = states[index(x, y, z)];
                    if (state == null) continue;
                    int emission = state.getLightEmission();
                    if (emission > 0) {
                        int idx = index(x, y, z);
                        blockLight[idx] = (byte) Math.max(blockLight[idx], emission);
                        queue.enqueue(pack(x, y, z));
                    }
                }
            }
        }
        floodFill(queue, blockLight);

        // --- Sky light: open columns get 15 from the top down ---
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                for (int y = sizeY - 1; y >= 0; y--) {
                    BlockState state = states[index(x, y, z)];
                    if (state != null && state.canOcclude()) {
                        break;
                    }
                    skyLight[index(x, y, z)] = 15;
                    queue.enqueue(pack(x, y, z));
                }
            }
        }
        floodFill(queue, skyLight);
    }

    private void floodFill(LongArrayFIFOQueue queue, byte[] light) {
        while (!queue.isEmpty()) {
            long packed = queue.dequeueLong();
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            int level = light[index(x, y, z)];
            if (level <= 1) continue;
            for (Direction direction : Direction.values()) {
                int nx = x + direction.getStepX();
                int ny = y + direction.getStepY();
                int nz = z + direction.getStepZ();
                if (!inBounds(nx, ny, nz)) continue;
                int nIdx = index(nx, ny, nz);
                BlockState neighbor = states[nIdx];
                if (neighbor != null && neighbor.canOcclude()) continue;
                int next = level - 1;
                if (light[nIdx] < next) {
                    light[nIdx] = (byte) next;
                    queue.enqueue(pack(nx, ny, nz));
                }
            }
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) x << 40) | ((long) y << 20) | z;
    }

    private static int unpackX(long packed) { return (int) (packed >>> 40); }
    private static int unpackY(long packed) { return (int) ((packed >>> 20) & 0xFFFFF); }
    private static int unpackZ(long packed) { return (int) (packed & 0xFFFFF); }

    // ==================== BlockAndTintGetter ====================

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (!inBounds(pos.getX(), pos.getY(), pos.getZ())) {
            return AIR;
        }
        BlockState state = states[index(pos.getX(), pos.getY(), pos.getZ())];
        return state != null ? state : AIR;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    /** Mirrors vanilla {@code Level.getShade} directional shading. */
    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0f;
        }
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    /** Never called: every light query is overridden below. */
    @Override
    public LevelLightEngine getLightEngine() {
        return null;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        if (!inBounds(pos.getX(), pos.getY(), pos.getZ())) {
            return layer == LightLayer.SKY ? ambientSkyLight : ambientBlockLight;
        }
        int idx = index(pos.getX(), pos.getY(), pos.getZ());
        if (layer == LightLayer.SKY) {
            return Math.max(skyLight[idx], ambientSkyLight > 0 ? Math.min(ambientSkyLight, 15) / 3 : 0);
        }
        return Math.max(blockLight[idx], ambientBlockLight / 3);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkenAmount) {
        int sky = getBrightness(LightLayer.SKY, pos) - darkenAmount;
        int block = getBrightness(LightLayer.BLOCK, pos);
        return Math.max(sky, block);
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return getBrightness(LightLayer.SKY, pos) >= 15;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) return GRASS_TINT;
        if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) return FOLIAGE_TINT;
        if (resolver == BiomeColors.WATER_COLOR_RESOLVER) return WATER_TINT;
        return GRASS_TINT;
    }

    @Override
    public int getHeight() {
        return sizeY;
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }
}
