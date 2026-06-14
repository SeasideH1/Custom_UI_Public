package com.lootmatrix.customui.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Server-side persistent data for the scoreboard overlay.
 * Stores team configs, scoreboard bindings, visibility, and previous-tick values for delta sync.
 */
public class ScoreboardOverlayData extends SavedData {

    private static final String DATA_NAME = "customui_scoreboard_overlay";

    // Team A config
    public String teamAIconPath = "";
    public int teamABarColor = 0xFFFF0000; // Red
    public String teamAProgressObjective = "";
    public String teamAProgressHolder = "";
    public String teamAMaxObjective = "";
    public String teamAMaxHolder = "";
    public String teamAScoreObjective = "";
    public String teamAScoreHolder = "";
    // Direct value overrides (used when objective is empty)
    public float teamADirectProgress = -1f;  // -1 = use scoreboard binding
    public int teamADirectScore = -1;        // -1 = use scoreboard binding

    // Team B config
    public String teamBIconPath = "";
    public int teamBBarColor = 0xFF0088FF; // Blue
    public String teamBProgressObjective = "";
    public String teamBProgressHolder = "";
    public String teamBMaxObjective = "";
    public String teamBMaxHolder = "";
    public String teamBScoreObjective = "";
    public String teamBScoreHolder = "";
    // Direct value overrides (used when objective is empty)
    public float teamBDirectProgress = -1f;  // -1 = use scoreboard binding
    public int teamBDirectScore = -1;        // -1 = use scoreboard binding

    // Timer config
    public String timerObjective = "";
    public String timerHolder = "";
    public int timerDirectTicks = -1;        // -1 = use scoreboard binding
    public int timerColor = 0xFFFFFF00;  // Default yellow
    public int timerTempColor = 0xFFFFFF00;  // Temporary color
    public int timerTempDuration = 0;  // Remaining ticks for temp color
    public boolean timerColorSwitch = true;  // true = switch mode, false = countdown mode

    // Visibility
    public boolean defaultVisible = false;
    public final Set<UUID> visibilityOverrides = new HashSet<>(); // players with overridden visibility
    public final Map<UUID, Boolean> playerVisibility = new HashMap<>(); // true=force show, false=force hide

    // Glow effect mode: "none"=off, "change"=flash on progress change, "leading"=moving bar effect
    public String glowMode = "none";

    // Progress fill direction reverse toggle
    public boolean reverseFillDirection = false;

    // ==================== Live values (not saved, computed each tick) ====================
    public transient float teamAProgress = 0f;
    public transient int teamAScore = 0;
    public transient float teamBProgress = 0f;
    public transient int teamBScore = 0;
    public transient int timerTicks = 0;

    // Previous tick values for delta detection
    public transient float prevTeamAProgress = -1f;
    public transient int prevTeamAScore = -1;
    public transient float prevTeamBProgress = -1f;
    public transient int prevTeamBScore = -1;
    public transient int prevTimerTicks = -1;

    // Config version counter - incremented when config changes, forces full resync
    public transient int configVersion = 0;
    public transient int lastSyncedConfigVersion = -1;
    public transient final Map<UUID, Integer> playerSyncedConfigVersion = new HashMap<>();
    public transient final Map<UUID, Boolean> playerLastVisibleState = new HashMap<>();

    public ScoreboardOverlayData() {
        super();
    }

    public boolean isVisibleTo(UUID playerUuid) {
        if (playerVisibility.containsKey(playerUuid)) {
            return playerVisibility.get(playerUuid);
        }
        return defaultVisible;
    }

    public void setPlayerVisibility(UUID playerUuid, boolean visible) {
        playerVisibility.put(playerUuid, visible);
        visibilityOverrides.add(playerUuid);
        setDirty();
    }

    public void clearPlayerVisibility(UUID playerUuid) {
        playerVisibility.remove(playerUuid);
        visibilityOverrides.remove(playerUuid);
        playerSyncedConfigVersion.remove(playerUuid);
        playerLastVisibleState.remove(playerUuid);
        setDirty();
    }

    public void resetAll() {
        teamAIconPath = "";
        teamABarColor = 0xFFFF0000;
        teamAProgressObjective = "";
        teamAProgressHolder = "";
        teamAMaxObjective = "";
        teamAMaxHolder = "";
        teamAScoreObjective = "";
        teamAScoreHolder = "";
        teamADirectProgress = -1f;
        teamADirectScore = -1;
        teamBIconPath = "";
        teamBBarColor = 0xFF0088FF;
        teamBProgressObjective = "";
        teamBProgressHolder = "";
        teamBMaxObjective = "";
        teamBMaxHolder = "";
        teamBScoreObjective = "";
        teamBScoreHolder = "";
        teamBDirectProgress = -1f;
        teamBDirectScore = -1;
        timerObjective = "";
        timerHolder = "";
        timerDirectTicks = -1;
        timerColor = 0xFFFFFF00;
        timerTempColor = 0xFFFFFF00;
        timerTempDuration = 0;
        timerColorSwitch = true;
        defaultVisible = false;
        visibilityOverrides.clear();
        playerVisibility.clear();
        playerSyncedConfigVersion.clear();
        playerLastVisibleState.clear();
        glowMode = "none";
        reverseFillDirection = false;
        configVersion++;
        setDirty();
    }

    public void markConfigChanged() {
        configVersion++;
        setDirty();
    }

    public void markTimerColorChanged() {
        // Timer color changes need immediate sync
        configVersion++;
        setDirty();
    }

    /**
     * Build a delta bitmask for changed values.
     * Bit 0: teamAProgress, Bit 1: teamAScore, Bit 2: teamBProgress, Bit 3: teamBScore, Bit 4: timerTicks
     */
    public byte getDeltaMask() {
        byte mask = 0;
        if (Float.compare(teamAProgress, prevTeamAProgress) != 0) mask |= 0x01;
        if (teamAScore != prevTeamAScore) mask |= 0x02;
        if (Float.compare(teamBProgress, prevTeamBProgress) != 0) mask |= 0x04;
        if (teamBScore != prevTeamBScore) mask |= 0x08;
        if (timerTicks != prevTimerTicks) mask |= 0x10;
        return mask;
    }

    public void snapshotPrevious() {
        prevTeamAProgress = teamAProgress;
        prevTeamAScore = teamAScore;
        prevTeamBProgress = teamBProgress;
        prevTeamBScore = teamBScore;
        prevTimerTicks = timerTicks;
    }

    // ==================== SavedData persistence ====================

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString("teamAIcon", teamAIconPath);
        tag.putInt("teamABarColor", teamABarColor);
        tag.putString("teamAProgressObj", teamAProgressObjective);
        tag.putString("teamAProgressHolder", teamAProgressHolder);
        tag.putString("teamAMaxObj", teamAMaxObjective);
        tag.putString("teamAMaxHolder", teamAMaxHolder);
        tag.putString("teamAScoreObj", teamAScoreObjective);
        tag.putString("teamAScoreHolder", teamAScoreHolder);
        tag.putFloat("teamADirectProgress", teamADirectProgress);
        tag.putInt("teamADirectScore", teamADirectScore);

        tag.putString("teamBIcon", teamBIconPath);
        tag.putInt("teamBBarColor", teamBBarColor);
        tag.putString("teamBProgressObj", teamBProgressObjective);
        tag.putString("teamBProgressHolder", teamBProgressHolder);
        tag.putString("teamBMaxObj", teamBMaxObjective);
        tag.putString("teamBMaxHolder", teamBMaxHolder);
        tag.putString("teamBScoreObj", teamBScoreObjective);
        tag.putString("teamBScoreHolder", teamBScoreHolder);
        tag.putFloat("teamBDirectProgress", teamBDirectProgress);
        tag.putInt("teamBDirectScore", teamBDirectScore);

        tag.putString("timerObj", timerObjective);
        tag.putString("timerHolder", timerHolder);
        tag.putInt("timerColor", timerColor);
        tag.putInt("timerDirectTicks", timerDirectTicks);

        tag.putBoolean("defaultVisible", defaultVisible);
        tag.putString("glowMode", glowMode);
        tag.putBoolean("reverseFillDirection", reverseFillDirection);

        // Save per-player visibility
        CompoundTag visTag = new CompoundTag();
        for (var entry : playerVisibility.entrySet()) {
            visTag.putBoolean(entry.getKey().toString(), entry.getValue());
        }
        tag.put("playerVisibility", visTag);

        return tag;
    }

    public static ScoreboardOverlayData load(CompoundTag tag) {
        ScoreboardOverlayData data = new ScoreboardOverlayData();
        data.teamAIconPath = tag.getString("teamAIcon");
        data.teamABarColor = tag.getInt("teamABarColor");
        data.teamAProgressObjective = tag.getString("teamAProgressObj");
        data.teamAProgressHolder = tag.getString("teamAProgressHolder");
        data.teamAMaxObjective = tag.getString("teamAMaxObj");
        data.teamAMaxHolder = tag.getString("teamAMaxHolder");
        data.teamAScoreObjective = tag.getString("teamAScoreObj");
        data.teamAScoreHolder = tag.getString("teamAScoreHolder");
        data.teamADirectProgress = tag.contains("teamADirectProgress") ? tag.getFloat("teamADirectProgress") : -1f;
        data.teamADirectScore = tag.contains("teamADirectScore") ? tag.getInt("teamADirectScore") : -1;

        data.teamBIconPath = tag.getString("teamBIcon");
        data.teamBBarColor = tag.getInt("teamBBarColor");
        data.teamBProgressObjective = tag.getString("teamBProgressObj");
        data.teamBProgressHolder = tag.getString("teamBProgressHolder");
        data.teamBMaxObjective = tag.getString("teamBMaxObj");
        data.teamBMaxHolder = tag.getString("teamBMaxHolder");
        data.teamBScoreObjective = tag.getString("teamBScoreObj");
        data.teamBScoreHolder = tag.getString("teamBScoreHolder");
        data.teamBDirectProgress = tag.contains("teamBDirectProgress") ? tag.getFloat("teamBDirectProgress") : -1f;
        data.teamBDirectScore = tag.contains("teamBDirectScore") ? tag.getInt("teamBDirectScore") : -1;

        data.timerObjective = tag.getString("timerObj");
        data.timerHolder = tag.getString("timerHolder");
        data.timerColor = tag.contains("timerColor") ? tag.getInt("timerColor") : 0xFFFFFF00;
        data.timerTempColor = data.timerColor;
        data.timerTempDuration = 0;
        data.timerColorSwitch = true;
        data.timerDirectTicks = tag.contains("timerDirectTicks") ? tag.getInt("timerDirectTicks") : -1;

        data.defaultVisible = tag.getBoolean("defaultVisible");
        // Handle both legacy int and new String format for glowMode
        if (tag.contains("glowMode", 8)) { // 8 = String type
            data.glowMode = tag.getString("glowMode");
        } else if (tag.contains("glowMode", 3)) { // 3 = Int type (legacy)
            int legacyMode = tag.getInt("glowMode");
            data.glowMode = switch (legacyMode) {
                case 1 -> "leading";
                case 2 -> "change";
                default -> "none";
            };
        } else {
            data.glowMode = "none";
        }
        data.reverseFillDirection = tag.getBoolean("reverseFillDirection");

        if (tag.contains("playerVisibility")) {
            CompoundTag visTag = tag.getCompound("playerVisibility");
            for (String key : visTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    data.playerVisibility.put(uuid, visTag.getBoolean(key));
                    data.visibilityOverrides.add(uuid);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        return data;
    }

    @Nullable
    public static ScoreboardOverlayData get(MinecraftServer server) {
        if (server == null || server.overworld() == null) return null;
        return server.overworld().getDataStorage()
                .computeIfAbsent(ScoreboardOverlayData::load, ScoreboardOverlayData::new, DATA_NAME);
    }
}

