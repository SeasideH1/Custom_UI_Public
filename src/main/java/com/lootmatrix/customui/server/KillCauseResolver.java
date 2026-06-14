package com.lootmatrix.customui.server;

import com.lootmatrix.customui.network.KillMessagePacket;

/**
 * Resolves kill feed icon type from the actual death cause first.
 * Held weapon icons are candidates, not proof that the held weapon caused the kill.
 */
public final class KillCauseResolver {

    static final String GENERIC_ICON = "customui:textures/overlay/generic.png";
    static final String MELEE_ICON = "customui:textures/overlay/melee.png";
    static final String EXPLOSION_ICON = "customui:textures/overlay/explosion.png";
    static final String FALL_ICON = "customui:textures/overlay/fall.png";
    static final String VOID_ICON = "customui:textures/overlay/void.png";
    static final String DROWN_ICON = "customui:textures/overlay/drown.png";
    static final String FIRE_ICON = "customui:textures/overlay/fire.png";

    private KillCauseResolver() {
    }

    public static Result resolve(String msgId, String directEntityId,
                                 String heldTaczIconPath, String heldSbwIconPath,
                                 boolean recentTaczHit) {
        if (isExplosionDamage(msgId, directEntityId)) {
            return new Result(EXPLOSION_ICON, KillMessagePacket.ICON_OTHER);
        }

        String explicitIcon = explicitNonWeaponIcon(msgId);
        if (explicitIcon != null) {
            return new Result(explicitIcon, KillMessagePacket.ICON_OTHER);
        }

        if (recentTaczHit) {
            return new Result(nonBlankOrDefault(heldTaczIconPath, GENERIC_ICON), KillMessagePacket.ICON_TACZ);
        }

        if ("player".equals(msgId)) {
            return new Result(MELEE_ICON, KillMessagePacket.ICON_MELEE);
        }

        if (isNotBlank(heldSbwIconPath)) {
            return new Result(heldSbwIconPath, KillMessagePacket.ICON_SBW);
        }

        return new Result(fallbackIcon(msgId), KillMessagePacket.ICON_OTHER);
    }

    public static boolean isExplicitOtherDamageSource(String msgId, String directEntityId) {
        return isExplosionDamage(msgId, directEntityId) || explicitNonWeaponIcon(msgId) != null;
    }

    private static boolean isExplosionDamage(String msgId, String directEntityId) {
        return "explosion".equals(msgId)
                || "explosion.player".equals(msgId)
                || containsAny(directEntityId, "grenade", "explosive", "explosion", "bomb", "tnt");
    }

    private static String explicitNonWeaponIcon(String msgId) {
        return switch (msgId == null ? "" : msgId) {
            case "fall" -> FALL_ICON;
            case "outOfWorld" -> VOID_ICON;
            case "drown" -> DROWN_ICON;
            case "lava", "inFire", "onFire" -> FIRE_ICON;
            default -> null;
        };
    }

    private static String fallbackIcon(String msgId) {
        return switch (msgId == null ? "" : msgId) {
            case "player" -> MELEE_ICON;
            default -> GENERIC_ICON;
        };
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return isNotBlank(value) ? value : fallback;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record Result(String path, byte type) {
    }
}
