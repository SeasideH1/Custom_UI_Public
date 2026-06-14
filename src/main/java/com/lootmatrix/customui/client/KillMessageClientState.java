package com.lootmatrix.customui.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side state for kill message visibility and display mode.
 */
@OnlyIn(Dist.CLIENT)
public class KillMessageClientState {

    private static final KillMessageClientState INSTANCE = new KillMessageClientState();

    /**
     * Display mode for kill messages.
     * - AllyTeam: Green for same team kills, red for enemy team kills (default)
     * - AllyPlayer: Green for own kills, red for other players' kills
     */
    public enum DisplayMode {
        ALLY_TEAM,    // Green = same team, Red = enemy team
        ALLY_PLAYER   // Green = own kill, Red = other player's kill
    }

    private boolean enabled = true;
    private DisplayMode displayMode = DisplayMode.ALLY_TEAM;

    public static KillMessageClientState getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            KillMessageOverlayRenderer.getInstance().clearMessages();
        }
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(DisplayMode mode) {
        this.displayMode = mode;
    }

    /**
     * Set display mode from string.
     * @param modeStr "AllyTeam" or "AllyPlayer"
     */
    public void setDisplayModeFromString(String modeStr) {
        if ("AllyPlayer".equalsIgnoreCase(modeStr)) {
            this.displayMode = DisplayMode.ALLY_PLAYER;
        } else {
            this.displayMode = DisplayMode.ALLY_TEAM;
        }
    }

    public String getDisplayModeString() {
        return displayMode == DisplayMode.ALLY_PLAYER ? "AllyPlayer" : "AllyTeam";
    }
}
