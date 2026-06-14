package com.lootmatrix.customui.mixin;

import com.google.gson.annotations.SerializedName;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.resource.pojo.data.gun.InaccuracyType;
import com.tacz.guns.util.HitboxHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import static com.tacz.guns.resource.pojo.data.gun.InaccuracyType.*;

@Mixin( value =  InaccuracyType.class, remap = false)
public abstract class TaczInaccuracyTypeMixin {

    private static boolean isMove(LivingEntity livingEntity) {
        double distance = (double)Math.abs(livingEntity.walkDist - livingEntity.walkDistO);
        if (livingEntity instanceof Player player) {
            distance = HitboxHelper.getPlayerVelocity(player).length();
        }

        return distance > (double)0.05F;
    }


    private static boolean isAirRising(LivingEntity livingEntity) {

        // 基础空中上升判断（所有 LivingEntity 通用）
        if (livingEntity.onGround()) return false;
        // if (livingEntity.getDeltaMovement().y <= 0.08) return false;
        if (livingEntity.isFallFlying()) return false;
        if (livingEntity.isSwimming()) return false;
        if (livingEntity.isInWater()) return false;
        if (livingEntity.onClimbable()) return false;

        // 仅 Player 需要排除飞行能力
        if (livingEntity instanceof Player player) {
            if (player.getAbilities().flying) return false;
        }

        return true;
    }



    /**
     * @author CustomUI
     * @reason Modify the getInaccuracyType method to make the inaccuracy type SNEAK when aiming and jumping. 哎哟跳枪闹麻了 | 珍爱生命远离跳枪。
     */
    @Overwrite
    public static InaccuracyType getInaccuracyType(LivingEntity livingEntity) {
        float aimingProgress = IGunOperator.fromLivingEntity(livingEntity).getSynAimingProgress();
        if (aimingProgress == 1.0F) {
            if(isAirRising(livingEntity)){
                return LIE;
            }else {
                return AIM;
            }
        } else if (!livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING) {
            return LIE;
        } else if (livingEntity.getPose() == Pose.CROUCHING) {
            return SNEAK;
        } else {
            return isMove(livingEntity) ? MOVE : STAND;
        }
    }
}
