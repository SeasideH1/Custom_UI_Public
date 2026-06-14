package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Network packet for sending damage information from server to client.
 * Contains entity UUID, damage amount, kill status, and critical hit status.
 */
public class DamageNumberPacket {

    private final UUID entityUUID;
    private final float damage;
    private final boolean isKill;
    private final boolean isCritical;
    private final boolean isHeadshot;
    private final long killEventId;

    /**
     * Create a new damage number packet.
     *
     * @param entityUUID UUID of the damaged entity
     * @param damage     Amount of damage dealt
     * @param isKill     Whether this damage killed the entity
     * @param isCritical Whether this was a critical hit
     */
    public DamageNumberPacket(UUID entityUUID, float damage, boolean isKill, boolean isCritical,
                              boolean isHeadshot, long killEventId) {
        this.entityUUID = entityUUID;
        this.damage = damage;
        this.isKill = isKill;
        this.isCritical = isCritical;
        this.isHeadshot = isHeadshot;
        this.killEventId = killEventId;
    }

    public DamageNumberPacket(UUID entityUUID, float damage, boolean isKill, boolean isCritical) {
        this(entityUUID, damage, isKill, isCritical, isCritical, 0L);
    }

    /**
     * Legacy constructor without critical hit info.
     */
    public DamageNumberPacket(UUID entityUUID, float damage, boolean isKill) {
        this(entityUUID, damage, isKill, false, false, 0L);
    }

    /**
     * Decode packet from network buffer.
     */
    public DamageNumberPacket(FriendlyByteBuf buf) {
        this.entityUUID = buf.readUUID();
        this.damage = buf.readFloat();
        this.isKill = buf.readBoolean();
        this.isCritical = buf.readBoolean();
        this.isHeadshot = buf.readBoolean();
        this.killEventId = buf.readLong();
    }

    /**
     * Encode packet to network buffer.
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(entityUUID);
        buf.writeFloat(damage);
        buf.writeBoolean(isKill);
        buf.writeBoolean(isCritical);
        buf.writeBoolean(isHeadshot);
        buf.writeLong(killEventId);
    }

    /**
     * Handle packet on client side.
     */
    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Execute on client side only using DistExecutor
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    DamageNumberClientHandler.handleDamagePacket(entityUUID, damage, isKill, isCritical,
                            isHeadshot, killEventId));
        });
        context.setPacketHandled(true);
    }

    // ==================== Getters ====================

    public UUID getEntityUUID() {
        return entityUUID;
    }

    public float getDamage() {
        return damage;
    }

    public boolean isKill() {
        return isKill;
    }

    public boolean isCritical() {
        return isCritical;
    }

    public boolean isHeadshot() {
        return isHeadshot;
    }

    public long getKillEventId() {
        return killEventId;
    }
}


