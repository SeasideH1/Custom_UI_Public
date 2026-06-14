package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Network packet to send damage indicator information from server to client.
 * Contains the damage source position and damage amount.
 */
public class DamageIndicatorPacket {

    private static final Logger LOGGER = LoggerFactory.getLogger(DamageIndicatorPacket.class);

    private final boolean hasSourcePos;
    private final double sourceX;
    private final double sourceY;
    private final double sourceZ;
    private final float damageAmount;

    /**
     * Constructor for known damage source position
     */
    public DamageIndicatorPacket(Vec3 sourcePos, float damageAmount) {
        if (sourcePos != null) {
            this.hasSourcePos = true;
            this.sourceX = sourcePos.x;
            this.sourceY = sourcePos.y;
            this.sourceZ = sourcePos.z;
        } else {
            this.hasSourcePos = false;
            this.sourceX = 0;
            this.sourceY = 0;
            this.sourceZ = 0;
        }
        this.damageAmount = damageAmount;
    }

    /**
     * Decoder constructor - reads from network buffer
     */
    public DamageIndicatorPacket(FriendlyByteBuf buf) {
        this.hasSourcePos = buf.readBoolean();
        if (this.hasSourcePos) {
            this.sourceX = buf.readDouble();
            this.sourceY = buf.readDouble();
            this.sourceZ = buf.readDouble();
        } else {
            this.sourceX = 0;
            this.sourceY = 0;
            this.sourceZ = 0;
        }
        this.damageAmount = buf.readFloat();
    }

    /**
     * Encoder - writes to network buffer
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(hasSourcePos);
        if (hasSourcePos) {
            buf.writeDouble(sourceX);
            buf.writeDouble(sourceY);
            buf.writeDouble(sourceZ);
        }
        buf.writeFloat(damageAmount);
    }

    /**
     * Handler - processes the packet on the client
     */
    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // LOGGER.debug("[DamageIndicatorPacket] Received packet - hasSourcePos: {}, damage: {}", hasSourcePos, damageAmount);
            // Execute on client thread using separate client handler class to avoid class loading issues on servers
            Vec3 sourcePos = hasSourcePos ? new Vec3(sourceX, sourceY, sourceZ) : null;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                // LOGGER.debug("[DamageIndicatorPacket] Adding damage indicator - sourcePos: {}", sourcePos);
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleDamageIndicator",
                        new Class<?>[]{Vec3.class, float.class},
                        sourcePos, damageAmount
                );
            });
        });
        context.setPacketHandled(true);
    }
}

