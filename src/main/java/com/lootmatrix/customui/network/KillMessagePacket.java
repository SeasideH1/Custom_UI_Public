package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Packet to send a kill message to the client.
 * iconType: 0=TACZ gun, 1=SBW gun, 2=melee, 3=other/generic
 */
public class KillMessagePacket {

    /** Icon type constants */
    public static final byte ICON_TACZ = 0;
    public static final byte ICON_SBW = 1;
    public static final byte ICON_MELEE = 2;
    public static final byte ICON_OTHER = 3;

    public final String killerName;
    public final String victimName;
    public final String weaponIconPath;
    public final boolean killerIsTeammate;
    public final boolean victimIsTeammate;
    public final boolean isHeadshot;
    public final byte iconType;
    public final boolean killerIsLocalPlayer;  // True if the killer is the receiving player
    public final boolean victimIsLocalPlayer;  // True if the victim is the receiving player
    public final UUID killerUuid;
    public final UUID victimUuid;

    public KillMessagePacket(String killerName, String victimName, String weaponIconPath,
                              boolean killerIsTeammate, boolean victimIsTeammate, boolean isHeadshot,
                              byte iconType, boolean killerIsLocalPlayer, boolean victimIsLocalPlayer) {
        this(killerName, victimName, weaponIconPath, killerIsTeammate, victimIsTeammate, isHeadshot,
                iconType, killerIsLocalPlayer, victimIsLocalPlayer, null, null);
    }

    public KillMessagePacket(String killerName, String victimName, String weaponIconPath,
                              boolean killerIsTeammate, boolean victimIsTeammate, boolean isHeadshot,
                              byte iconType, boolean killerIsLocalPlayer, boolean victimIsLocalPlayer,
                              UUID killerUuid, UUID victimUuid) {
        this.killerName = killerName;
        this.victimName = victimName;
        this.weaponIconPath = weaponIconPath;
        this.killerIsTeammate = killerIsTeammate;
        this.victimIsTeammate = victimIsTeammate;
        this.isHeadshot = isHeadshot;
        this.iconType = iconType;
        this.killerIsLocalPlayer = killerIsLocalPlayer;
        this.victimIsLocalPlayer = victimIsLocalPlayer;
        this.killerUuid = killerUuid;
        this.victimUuid = victimUuid;
    }

    public KillMessagePacket(FriendlyByteBuf buf) {
        this.killerName = buf.readUtf(128);
        this.victimName = buf.readUtf(128);
        this.weaponIconPath = buf.readUtf(256);
        this.killerIsTeammate = buf.readBoolean();
        this.victimIsTeammate = buf.readBoolean();
        this.isHeadshot = buf.readBoolean();
        this.iconType = buf.readByte();
        this.killerIsLocalPlayer = buf.readBoolean();
        this.victimIsLocalPlayer = buf.readBoolean();
        this.killerUuid = readNullableUuid(buf);
        this.victimUuid = readNullableUuid(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(killerName, 128);
        buf.writeUtf(victimName, 128);
        buf.writeUtf(weaponIconPath, 256);
        buf.writeBoolean(killerIsTeammate);
        buf.writeBoolean(victimIsTeammate);
        buf.writeBoolean(isHeadshot);
        buf.writeByte(iconType);
        buf.writeBoolean(killerIsLocalPlayer);
        buf.writeBoolean(victimIsLocalPlayer);
        writeNullableUuid(buf, killerUuid);
        writeNullableUuid(buf, victimUuid);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleKillMessage",
                        new Class<?>[]{
                                String.class, String.class, String.class,
                                boolean.class, boolean.class, boolean.class, byte.class,
                                boolean.class, boolean.class, UUID.class, UUID.class
                        },
                        killerName, victimName, weaponIconPath,
                        killerIsTeammate, victimIsTeammate, isHeadshot, iconType,
                        killerIsLocalPlayer, victimIsLocalPlayer, killerUuid, victimUuid
                )
        ));
        ctx.setPacketHandled(true);
    }

    private static UUID readNullableUuid(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    private static void writeNullableUuid(FriendlyByteBuf buf, UUID uuid) {
        buf.writeBoolean(uuid != null);
        if (uuid != null) {
            buf.writeUUID(uuid);
        }
    }
}

