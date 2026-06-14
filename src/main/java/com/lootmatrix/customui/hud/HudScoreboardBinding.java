package com.lootmatrix.customui.hud;

import javax.annotation.Nullable;

/**
 * Parsed "scoreboard:objective[:holder]" data source binding.
 * Holder "@s" (the default when omitted) means "the viewing player" and is
 * resolved per player on the server.
 */
public final class HudScoreboardBinding {

    public static final String PREFIX = "scoreboard:";
    public static final String SELF_HOLDER = "@s";

    public final String objective;
    public final String holder;

    private HudScoreboardBinding(String objective, String holder) {
        this.objective = objective;
        this.holder = holder;
    }

    /** Parse a dataSource string; null when it is not a scoreboard binding. */
    @Nullable
    public static HudScoreboardBinding parse(@Nullable String dataSource) {
        if (dataSource == null || !dataSource.startsWith(PREFIX)) return null;
        String body = dataSource.substring(PREFIX.length());
        if (body.isEmpty()) return null;
        int colon = body.indexOf(':');
        if (colon < 0) {
            return new HudScoreboardBinding(body, SELF_HOLDER);
        }
        String objective = body.substring(0, colon);
        String holder = body.substring(colon + 1);
        if (objective.isEmpty()) return null;
        return new HudScoreboardBinding(objective, holder.isEmpty() ? SELF_HOLDER : holder);
    }

    public boolean isSelf() {
        return SELF_HOLDER.equals(holder);
    }

    /** Stable cache/sync key ("objective\u0000holder"). */
    public String key() {
        return key(objective, holder);
    }

    public static String key(String objective, String holder) {
        return objective + '\u0000' + holder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HudScoreboardBinding other)) return false;
        return objective.equals(other.objective) && holder.equals(other.holder);
    }

    @Override
    public int hashCode() {
        return objective.hashCode() * 31 + holder.hashCode();
    }
}
