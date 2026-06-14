package com.lootmatrix.customui.hud;

import javax.annotation.Nullable;

/**
 * Parsed {@code entity:<selector>:<field>} data source binding.
 *
 * <p>The selector may contain colons (e.g. {@code minecraft:zombie}); the field
 * is always the segment after the last colon. NBT paths use the {@code nbt.}
 * prefix: {@code entity:@e[tag=boss]:nbt.CustomBoss.Phase}.
 */
public final class HudEntityBinding {

    public static final String PREFIX = "entity:";

    public enum Field {
        /** Display name ({@link net.minecraft.world.entity.Entity#getDisplayName()}). */
        NAME,
        /** Raw custom name component text; empty when unset. */
        CUSTOM_NAME,
        HEALTH,
        MAX_HEALTH,
        HEALTH_PCT,
        ARMOR,
        ABSORPTION,
        FOOD,
        SATURATION,
        XP_LEVEL,
        GAMEMODE,
        SCOREBOARD_NAME,
        TYPE,
        UUID,
        ENTITY_ID,
        DIMENSION,
        TEAM,
        BABY,
        ON_GROUND,
        POS_X,
        POS_Y,
        POS_Z,
        YAW,
        PITCH,
        DISTANCE,
        SPEED,
        FIRE_TICKS,
        AIR,
        NBT
    }

    public final String selector;
    public final Field field;
    /** Dot-separated NBT path when {@link #field} is {@link Field#NBT}. */
    @Nullable public final String nbtPath;

    private HudEntityBinding(String selector, Field field, @Nullable String nbtPath) {
        this.selector = selector;
        this.field = field;
        this.nbtPath = nbtPath;
    }

    /** Parse a dataSource string; null when it is not an entity binding. */
    @Nullable
    public static HudEntityBinding parse(@Nullable String dataSource) {
        if (dataSource == null || !dataSource.startsWith(PREFIX)) return null;
        String body = dataSource.substring(PREFIX.length());
        if (body.isEmpty()) return null;
        int lastColon = body.lastIndexOf(':');
        if (lastColon <= 0 || lastColon >= body.length() - 1) return null;
        String selector = body.substring(0, lastColon).trim();
        String fieldToken = body.substring(lastColon + 1).trim();
        if (selector.isEmpty() || fieldToken.isEmpty()) return null;

        if (fieldToken.startsWith("nbt.")) {
            String path = fieldToken.substring(4).trim();
            if (path.isEmpty()) return null;
            return new HudEntityBinding(selector, Field.NBT, path);
        }

        Field field = parseFieldToken(fieldToken);
        if (field == null) return null;
        return new HudEntityBinding(selector, field, null);
    }

    @Nullable
    private static Field parseFieldToken(String token) {
        return switch (token) {
            case "name", "display_name" -> Field.NAME;
            case "custom_name", "customName" -> Field.CUSTOM_NAME;
            case "health" -> Field.HEALTH;
            case "max_health", "maxHealth" -> Field.MAX_HEALTH;
            case "health_pct", "healthPct", "health_percent" -> Field.HEALTH_PCT;
            case "armor", "armor_value" -> Field.ARMOR;
            case "absorption" -> Field.ABSORPTION;
            case "food", "food_level", "hunger" -> Field.FOOD;
            case "saturation" -> Field.SATURATION;
            case "xp_level", "level", "experience_level" -> Field.XP_LEVEL;
            case "gamemode", "game_mode" -> Field.GAMEMODE;
            case "scoreboard_name", "score_name" -> Field.SCOREBOARD_NAME;
            case "type", "entity_type" -> Field.TYPE;
            case "uuid" -> Field.UUID;
            case "id", "entity_id" -> Field.ENTITY_ID;
            case "dimension", "dim" -> Field.DIMENSION;
            case "team" -> Field.TEAM;
            case "baby", "is_baby" -> Field.BABY;
            case "on_ground", "grounded" -> Field.ON_GROUND;
            case "x", "pos_x" -> Field.POS_X;
            case "y", "pos_y" -> Field.POS_Y;
            case "z", "pos_z" -> Field.POS_Z;
            case "yaw", "rot_y" -> Field.YAW;
            case "pitch", "rot_x" -> Field.PITCH;
            case "distance", "dist" -> Field.DISTANCE;
            case "speed", "velocity" -> Field.SPEED;
            case "fire", "fire_ticks" -> Field.FIRE_TICKS;
            case "air", "air_supply" -> Field.AIR;
            default -> null;
        };
    }

    /** Stable cache/sync key ({@code selector\u0000fieldToken}). */
    public String key() {
        return key(selector, fieldToken());
    }

    public static String key(String selector, String fieldToken) {
        return selector + '\u0000' + fieldToken;
    }

    /** Reconstruct a binding from a sync key; null when malformed. */
    @Nullable
    public static HudEntityBinding fromKey(String key) {
        int sep = key.indexOf('\u0000');
        if (sep <= 0 || sep >= key.length() - 1) return null;
        String selector = key.substring(0, sep);
        String fieldToken = key.substring(sep + 1);
        if (fieldToken.startsWith("nbt.")) {
            String path = fieldToken.substring(4);
            if (path.isEmpty()) return null;
            return new HudEntityBinding(selector, Field.NBT, path);
        }
        return parse(PREFIX + selector + ':' + fieldToken);
    }

    /** Whether this field yields a numeric value suitable for PROGRESS bars. */
    public boolean isNumericField() {
        return switch (field) {
            case HEALTH, MAX_HEALTH, HEALTH_PCT, ARMOR, ABSORPTION, FOOD, SATURATION, XP_LEVEL,
                    ENTITY_ID, BABY, ON_GROUND, POS_X, POS_Y, POS_Z, YAW, PITCH, DISTANCE, SPEED,
                    FIRE_TICKS, AIR, NBT -> true;
            default -> false;
        };
    }

    private String fieldToken() {
        return switch (field) {
            case NAME -> "name";
            case CUSTOM_NAME -> "custom_name";
            case HEALTH -> "health";
            case MAX_HEALTH -> "max_health";
            case HEALTH_PCT -> "health_pct";
            case ARMOR -> "armor";
            case ABSORPTION -> "absorption";
            case FOOD -> "food";
            case SATURATION -> "saturation";
            case XP_LEVEL -> "xp_level";
            case GAMEMODE -> "gamemode";
            case SCOREBOARD_NAME -> "scoreboard_name";
            case TYPE -> "type";
            case UUID -> "uuid";
            case ENTITY_ID -> "entity_id";
            case DIMENSION -> "dimension";
            case TEAM -> "team";
            case BABY -> "baby";
            case ON_GROUND -> "on_ground";
            case POS_X -> "pos_x";
            case POS_Y -> "pos_y";
            case POS_Z -> "pos_z";
            case YAW -> "yaw";
            case PITCH -> "pitch";
            case DISTANCE -> "distance";
            case SPEED -> "speed";
            case FIRE_TICKS -> "fire_ticks";
            case AIR -> "air";
            case NBT -> "nbt." + nbtPath;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HudEntityBinding other)) return false;
        return selector.equals(other.selector) && field == other.field
                && java.util.Objects.equals(nbtPath, other.nbtPath);
    }

    @Override
    public int hashCode() {
        int h = selector.hashCode() * 31 + field.hashCode();
        return nbtPath != null ? h * 31 + nbtPath.hashCode() : h;
    }
}
