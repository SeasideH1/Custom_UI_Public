package com.lootmatrix.customui.server;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Mohist 兼容工具类。
 * Mohist 混合端的 CraftBukkit 层会拦截某些字段修改（如 selectedSlot、deltaMovement），
 * 导致服务端直接赋值无效。通过发包和标记强制同步来绕过该限制。
 */
public class MohistCompatUtil {

    /**
     * 安全修改服务端玩家的选中槽位，兼容 Mohist。
     * 同时设置字段值和发送同步包。
     */
    public static void setSelectedSlot(ServerPlayer player, int slot) {
        if (slot < 0 || slot > 8) return;
        player.getInventory().selected = slot;
        player.connection.send(new ClientboundSetCarriedItemPacket(slot));
    }

    /**
     * 安全修改实体运动向量，兼容 Mohist。
     * 调用 setDeltaMovement 后设置 hurtMarked = true 强制服务端下发速度同步包。
     * 若实体是 ServerPlayer 则额外发送速度包。
     */
    public static void setMotion(Entity entity, Vec3 motion) {
        entity.setDeltaMovement(motion);
        entity.hurtMarked = true;
        if (entity instanceof ServerPlayer sp) {
            sp.connection.send(new ClientboundSetEntityMotionPacket(entity));
        }
    }

    /**
     * 安全修改实体运动向量（分量形式）。
     */
    public static void setMotion(Entity entity, double x, double y, double z) {
        setMotion(entity, new Vec3(x, y, z));
    }
}
