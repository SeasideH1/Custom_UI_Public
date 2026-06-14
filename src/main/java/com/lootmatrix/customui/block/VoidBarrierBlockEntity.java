package com.lootmatrix.customui.block;

import com.lootmatrix.customui.ClientBootstrapBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for VoidBarrierBlock, used for custom depth rendering.
 */
public class VoidBarrierBlockEntity extends BlockEntity {

    public VoidBarrierBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.VOID_BARRIER.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            notifyClientAdd();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide) {
            notifyClientRemove();
        }
        super.setRemoved();
    }

    private void notifyClientAdd() {
        ClientBootstrapBridge.onVoidBarrierAdded(this.getBlockPos());
    }

    private void notifyClientRemove() {
        ClientBootstrapBridge.onVoidBarrierRemoved(this.getBlockPos());
    }
}

