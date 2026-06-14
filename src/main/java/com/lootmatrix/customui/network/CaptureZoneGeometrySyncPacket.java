package com.lootmatrix.customui.network;

import com.lootmatrix.customui.capturezone.CaptureZone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client packet to sync capture zone geometry definitions.
 * Sent when a zone is activated so the client can render 3D boundaries
 * and project zone markers via WorldToScreenUtil.
 */
public class CaptureZoneGeometrySyncPacket {

    private final String zoneId;
    private final String displayName;
    private final double originX, originY, originZ;
    private final List<ShapeData> shapes;

    /** Clear all client-side zone geometry when true. */
    private final boolean clearAll;

    public static class ShapeData {
        public final String type; // cylinder, box, sphere
        public final String mode; // add, subtract
        public final double centerX, centerY, centerZ;
        public final double radius, height;
        public final double minX, minY, minZ;
        public final double maxX, maxY, maxZ;

        public ShapeData(String type, String mode,
                         double centerX, double centerY, double centerZ,
                         double radius, double height,
                         double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {
            this.type = type; this.mode = mode;
            this.centerX = centerX; this.centerY = centerY; this.centerZ = centerZ;
            this.radius = radius; this.height = height;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    /** Construct a geometry sync for a specific zone. */
    public CaptureZoneGeometrySyncPacket(CaptureZone zone) {
        this.zoneId = zone.id;
        this.displayName = zone.displayName;
        this.originX = zone.originX;
        this.originY = zone.originY;
        this.originZ = zone.originZ;
        this.clearAll = false;
        this.shapes = new ArrayList<>();
        for (CaptureZone.ShapeOp op : zone.ops) {
            shapes.add(new ShapeData(
                    op.type.name().toLowerCase(), op.mode.name().toLowerCase(),
                    op.centerX, op.centerY, op.centerZ,
                    op.radius, op.height,
                    op.minX, op.minY, op.minZ,
                    op.maxX, op.maxY, op.maxZ
            ));
        }
    }

    /** Construct a clear-all packet. */
    public CaptureZoneGeometrySyncPacket() {
        this.zoneId = "";
        this.displayName = "";
        this.originX = 0; this.originY = 0; this.originZ = 0;
        this.clearAll = true;
        this.shapes = new ArrayList<>();
    }

    public CaptureZoneGeometrySyncPacket(FriendlyByteBuf buf) {
        this.clearAll = buf.readBoolean();
        this.zoneId = buf.readUtf(256);
        this.displayName = buf.readUtf(256);
        this.originX = buf.readDouble();
        this.originY = buf.readDouble();
        this.originZ = buf.readDouble();
        int count = buf.readVarInt();
        this.shapes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            shapes.add(new ShapeData(
                    buf.readUtf(32), buf.readUtf(16),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()
            ));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(clearAll);
        buf.writeUtf(zoneId, 256);
        buf.writeUtf(displayName, 256);
        buf.writeDouble(originX);
        buf.writeDouble(originY);
        buf.writeDouble(originZ);
        buf.writeVarInt(shapes.size());
        for (ShapeData s : shapes) {
            buf.writeUtf(s.type, 32);
            buf.writeUtf(s.mode, 16);
            buf.writeDouble(s.centerX); buf.writeDouble(s.centerY); buf.writeDouble(s.centerZ);
            buf.writeDouble(s.radius); buf.writeDouble(s.height);
            buf.writeDouble(s.minX); buf.writeDouble(s.minY); buf.writeDouble(s.minZ);
            buf.writeDouble(s.maxX); buf.writeDouble(s.maxY); buf.writeDouble(s.maxZ);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class<?> bridge = Class.forName("com.lootmatrix.customui.network.PacketClientBridge");
                if (clearAll) {
                    bridge.getMethod("handleCaptureZoneClearGeometry").invoke(null);
                } else {
                    bridge.getMethod("handleCaptureZoneGeometry",
                                    String.class, String.class,
                                    double.class, double.class, double.class,
                                    java.util.List.class)
                            .invoke(null, zoneId, displayName, originX, originY, originZ, shapes);
                }
            } catch (Exception ignored) {}
        });
        ctx.get().setPacketHandled(true);
    }
}
