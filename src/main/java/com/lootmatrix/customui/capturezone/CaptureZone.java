package com.lootmatrix.customui.capturezone;

import com.google.gson.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Data model for a capture zone defined via CSG (Constructive Solid Geometry) operations.
 * Each zone has an origin, voxel size, and a list of shape operations (add/subtract).
 * Player detection is performed by testing if the player's position falls inside
 * the union of all "add" shapes minus all "subtract" shapes.
 */
public class CaptureZone {

    public String id;
    public String displayName = "";
    public double originX, originY, originZ;
    public double voxelSize = 1.0;
    public List<ShapeOp> ops = new ArrayList<>();

    /** Capture time in ticks required to fully capture (default 10 seconds). */
    public int captureTimeTicks = 200;

    // ==================== FPS Game Mode Configuration ====================

    /** Game mode type: domination, koth (king of the hill), hardpoint, headquarters, ctf_flag */
    public String gameMode = "domination";
    /** Points awarded per tick while a team owns this zone (default 1 per second = 0.05 per tick). */
    public float scorePerTick = 0.05f;
    /** Score required to win the round (0 = no score-based win). */
    public int scoreLimit = 0;
    /** Round time limit in ticks (0 = no time limit). */
    public int roundTimeTicks = 0;
    /** Whether this zone should auto-rotate to a new position after capture (hardpoint mode). */
    public boolean rotateAfterCapture = false;
    /** Next zone id to activate after this zone is captured (for rotation chains). */
    @Nullable public String nextZoneId;
    /** Minimum players required in zone to begin capture. */
    public int minPlayersToCapture = 1;
    /** Whether the zone can be re-captured by another team after being owned. */
    public boolean allowRecapture = true;
    /** Lock zone after capture (headquarters mode: lock for N ticks, then rotate). */
    public int lockAfterCaptureTicks = 0;

    /** Optional mcfunction to run when a team starts capturing. */
    @Nullable public String onCaptureStartFunction;
    /** Optional mcfunction to run when capture completes. */
    @Nullable public String onCaptureCompleteFunction;
    /** Optional mcfunction to run when a zone is contested (multiple teams). */
    @Nullable public String onContestedFunction;
    /** Optional mcfunction to run on each capture tick for the capturing player. */
    @Nullable public String onCaptureTickFunction;

    public enum ShapeType { CYLINDER, BOX, SPHERE }
    public enum ShapeMode { ADD, SUBTRACT }

    public static class ShapeOp {
        public ShapeType type;
        public ShapeMode mode = ShapeMode.ADD;

        // Cylinder: center (x,y,z), radius, height (extends upward from center.y)
        public double centerX, centerY, centerZ;
        public double radius;
        public double height;

        // Box: min/max corners
        public double minX, minY, minZ;
        public double maxX, maxY, maxZ;

        /**
         * Test if a point is inside this shape.
         */
        public boolean contains(double px, double py, double pz) {
            switch (type) {
                case CYLINDER: {
                    double dx = px - centerX;
                    double dz = pz - centerZ;
                    if (dx * dx + dz * dz > radius * radius) return false;
                    return py >= centerY && py <= centerY + height;
                }
                case BOX:
                    return px >= minX && px <= maxX && py >= minY && py <= maxY && pz >= minZ && pz <= maxZ;
                case SPHERE: {
                    double dx = px - centerX;
                    double dy = py - centerY;
                    double dz = pz - centerZ;
                    return dx * dx + dy * dy + dz * dz <= radius * radius;
                }
            }
            return false;
        }

        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", type.name().toLowerCase());
            tag.putString("mode", mode.name().toLowerCase());
            tag.putDouble("centerX", centerX);
            tag.putDouble("centerY", centerY);
            tag.putDouble("centerZ", centerZ);
            tag.putDouble("radius", radius);
            tag.putDouble("height", height);
            tag.putDouble("minX", minX);
            tag.putDouble("minY", minY);
            tag.putDouble("minZ", minZ);
            tag.putDouble("maxX", maxX);
            tag.putDouble("maxY", maxY);
            tag.putDouble("maxZ", maxZ);
            return tag;
        }

        public static ShapeOp fromNbt(CompoundTag tag) {
            ShapeOp op = new ShapeOp();
            op.type = ShapeType.valueOf(tag.getString("type").toUpperCase());
            op.mode = ShapeMode.valueOf(tag.getString("mode").toUpperCase());
            op.centerX = tag.getDouble("centerX");
            op.centerY = tag.getDouble("centerY");
            op.centerZ = tag.getDouble("centerZ");
            op.radius = tag.getDouble("radius");
            op.height = tag.getDouble("height");
            op.minX = tag.getDouble("minX");
            op.minY = tag.getDouble("minY");
            op.minZ = tag.getDouble("minZ");
            op.maxX = tag.getDouble("maxX");
            op.maxY = tag.getDouble("maxY");
            op.maxZ = tag.getDouble("maxZ");
            return op;
        }

        public static ShapeOp fromJson(JsonObject json) {
            ShapeOp op = new ShapeOp();
            op.type = ShapeType.valueOf(GsonHelper.getAsString(json, "type", "cylinder").toUpperCase());
            op.mode = ShapeMode.valueOf(GsonHelper.getAsString(json, "mode", "add").toUpperCase());

            switch (op.type) {
                case CYLINDER -> {
                    if (json.has("center")) {
                        JsonArray c = json.getAsJsonArray("center");
                        op.centerX = c.get(0).getAsDouble();
                        op.centerY = c.get(1).getAsDouble();
                        op.centerZ = c.get(2).getAsDouble();
                    }
                    op.radius = GsonHelper.getAsDouble(json, "radius", 5.0);
                    op.height = GsonHelper.getAsDouble(json, "height", 3.0);
                }
                case BOX -> {
                    if (json.has("min")) {
                        JsonArray m = json.getAsJsonArray("min");
                        op.minX = m.get(0).getAsDouble();
                        op.minY = m.get(1).getAsDouble();
                        op.minZ = m.get(2).getAsDouble();
                    }
                    if (json.has("max")) {
                        JsonArray m = json.getAsJsonArray("max");
                        op.maxX = m.get(0).getAsDouble();
                        op.maxY = m.get(1).getAsDouble();
                        op.maxZ = m.get(2).getAsDouble();
                    }
                }
                case SPHERE -> {
                    if (json.has("center")) {
                        JsonArray c = json.getAsJsonArray("center");
                        op.centerX = c.get(0).getAsDouble();
                        op.centerY = c.get(1).getAsDouble();
                        op.centerZ = c.get(2).getAsDouble();
                    }
                    op.radius = GsonHelper.getAsDouble(json, "radius", 5.0);
                }
            }
            return op;
        }
    }

    /**
     * Test if a world-space point is inside this zone using CSG operations.
     * Process: evaluate all "add" shapes, then exclude all "subtract" shapes.
     */
    public boolean containsPoint(double px, double py, double pz) {
        boolean inside = false;
        for (ShapeOp op : ops) {
            if (op.mode == ShapeMode.ADD && op.contains(px, py, pz)) {
                inside = true;
            }
        }
        if (!inside) return false;
        for (ShapeOp op : ops) {
            if (op.mode == ShapeMode.SUBTRACT && op.contains(px, py, pz)) {
                return false;
            }
        }
        return true;
    }

    // ==================== JSON ====================

    public static CaptureZone fromJson(JsonObject json) {
        CaptureZone zone = new CaptureZone();
        zone.id = GsonHelper.getAsString(json, "id", "unknown");
        zone.displayName = GsonHelper.getAsString(json, "displayName", zone.id);

        if (json.has("origin")) {
            JsonArray o = json.getAsJsonArray("origin");
            zone.originX = o.get(0).getAsDouble();
            zone.originY = o.get(1).getAsDouble();
            zone.originZ = o.get(2).getAsDouble();
        }
        zone.voxelSize = GsonHelper.getAsDouble(json, "voxel_size", 1.0);
        zone.captureTimeTicks = GsonHelper.getAsInt(json, "captureTimeTicks", 200);

        // FPS game mode fields
        zone.gameMode = GsonHelper.getAsString(json, "gameMode", "domination");
        zone.scorePerTick = GsonHelper.getAsFloat(json, "scorePerTick", 0.05f);
        zone.scoreLimit = GsonHelper.getAsInt(json, "scoreLimit", 0);
        zone.roundTimeTicks = GsonHelper.getAsInt(json, "roundTimeTicks", 0);
        zone.rotateAfterCapture = GsonHelper.getAsBoolean(json, "rotateAfterCapture", false);
        zone.nextZoneId = json.has("nextZoneId") ? GsonHelper.getAsString(json, "nextZoneId") : null;
        zone.minPlayersToCapture = GsonHelper.getAsInt(json, "minPlayersToCapture", 1);
        zone.allowRecapture = GsonHelper.getAsBoolean(json, "allowRecapture", true);
        zone.lockAfterCaptureTicks = GsonHelper.getAsInt(json, "lockAfterCaptureTicks", 0);

        zone.onCaptureStartFunction = json.has("onCaptureStart") ? GsonHelper.getAsString(json, "onCaptureStart") : null;
        zone.onCaptureCompleteFunction = json.has("onCaptureComplete") ? GsonHelper.getAsString(json, "onCaptureComplete") : null;
        zone.onContestedFunction = json.has("onContested") ? GsonHelper.getAsString(json, "onContested") : null;
        zone.onCaptureTickFunction = json.has("onCaptureTick") ? GsonHelper.getAsString(json, "onCaptureTick") : null;

        if (json.has("ops")) {
            for (JsonElement el : json.getAsJsonArray("ops")) {
                zone.ops.add(ShapeOp.fromJson(el.getAsJsonObject()));
            }
        }
        return zone;
    }

    // ==================== NBT ====================

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("displayName", displayName);
        tag.putDouble("originX", originX);
        tag.putDouble("originY", originY);
        tag.putDouble("originZ", originZ);
        tag.putDouble("voxelSize", voxelSize);
        tag.putInt("captureTimeTicks", captureTimeTicks);
        tag.putString("gameMode", gameMode);
        tag.putFloat("scorePerTick", scorePerTick);
        tag.putInt("scoreLimit", scoreLimit);
        tag.putInt("roundTimeTicks", roundTimeTicks);
        tag.putBoolean("rotateAfterCapture", rotateAfterCapture);
        if (nextZoneId != null) tag.putString("nextZoneId", nextZoneId);
        tag.putInt("minPlayersToCapture", minPlayersToCapture);
        tag.putBoolean("allowRecapture", allowRecapture);
        tag.putInt("lockAfterCaptureTicks", lockAfterCaptureTicks);
        if (onCaptureStartFunction != null) tag.putString("onCaptureStart", onCaptureStartFunction);
        if (onCaptureCompleteFunction != null) tag.putString("onCaptureComplete", onCaptureCompleteFunction);
        if (onContestedFunction != null) tag.putString("onContested", onContestedFunction);
        if (onCaptureTickFunction != null) tag.putString("onCaptureTick", onCaptureTickFunction);

        ListTag opsList = new ListTag();
        for (ShapeOp op : ops) opsList.add(op.toNbt());
        tag.put("ops", opsList);
        return tag;
    }

    public static CaptureZone fromNbt(CompoundTag tag) {
        CaptureZone zone = new CaptureZone();
        zone.id = tag.getString("id");
        zone.displayName = tag.getString("displayName");
        zone.originX = tag.getDouble("originX");
        zone.originY = tag.getDouble("originY");
        zone.originZ = tag.getDouble("originZ");
        zone.voxelSize = tag.getDouble("voxelSize");
        zone.captureTimeTicks = tag.getInt("captureTimeTicks");
        zone.gameMode = tag.contains("gameMode") ? tag.getString("gameMode") : "domination";
        zone.scorePerTick = tag.contains("scorePerTick") ? tag.getFloat("scorePerTick") : 0.05f;
        zone.scoreLimit = tag.contains("scoreLimit") ? tag.getInt("scoreLimit") : 0;
        zone.roundTimeTicks = tag.contains("roundTimeTicks") ? tag.getInt("roundTimeTicks") : 0;
        zone.rotateAfterCapture = tag.contains("rotateAfterCapture") && tag.getBoolean("rotateAfterCapture");
        zone.nextZoneId = tag.contains("nextZoneId") ? tag.getString("nextZoneId") : null;
        zone.minPlayersToCapture = tag.contains("minPlayersToCapture") ? tag.getInt("minPlayersToCapture") : 1;
        zone.allowRecapture = !tag.contains("allowRecapture") || tag.getBoolean("allowRecapture");
        zone.lockAfterCaptureTicks = tag.contains("lockAfterCaptureTicks") ? tag.getInt("lockAfterCaptureTicks") : 0;
        zone.onCaptureStartFunction = tag.contains("onCaptureStart") ? tag.getString("onCaptureStart") : null;
        zone.onCaptureCompleteFunction = tag.contains("onCaptureComplete") ? tag.getString("onCaptureComplete") : null;
        zone.onContestedFunction = tag.contains("onContested") ? tag.getString("onContested") : null;
        zone.onCaptureTickFunction = tag.contains("onCaptureTick") ? tag.getString("onCaptureTick") : null;

        ListTag opsList = tag.getList("ops", Tag.TAG_COMPOUND);
        for (int i = 0; i < opsList.size(); i++) {
            zone.ops.add(ShapeOp.fromNbt(opsList.getCompound(i)));
        }
        return zone;
    }
}
