package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.ChlorideMonsterCullingDecisions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides Chloride's monster distance culling without bypassing the frustum test.
 */
@Mixin(value = EntityRenderDispatcher.class, priority = 1500)
public class ChlorideMonsterCullingMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void customui$overrideMonsterCulling(
            E entity, Frustum frustum, double camX, double camY, double camZ,
            CallbackInfoReturnable<Boolean> cir) {

        if (entity.getType().getCategory() != MobCategory.MONSTER) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return;
        }

        boolean passesFrustumCheck = entity.noCulling || frustum.isVisible(entity.getBoundingBoxForCulling());
        int renderChunks = mc.options.renderDistance().get();
        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        cir.setReturnValue(ChlorideMonsterCullingDecisions.shouldRenderMonster(
                passesFrustumCheck,
                renderChunks,
                dx,
                dy,
                dz
        ));
    }
}
