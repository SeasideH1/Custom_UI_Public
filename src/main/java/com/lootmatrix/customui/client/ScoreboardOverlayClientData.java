package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * Client-side cache for scoreboard overlay data received from the server.
 * Provides smooth interpolation for progress bars and tracks glow effect state.
 */
public class ScoreboardOverlayClientData {

    private static final ScoreboardOverlayClientData INSTANCE = new ScoreboardOverlayClientData();

    // Config
    @Nullable
    public ResourceLocation teamAIcon = null;
    public int teamABarColor = 0xFFFF0000;
    @Nullable
    public ResourceLocation teamBIcon = null;
    public int teamBBarColor = 0xFF0088FF;
    public String glowMode = "none";
    public boolean reverseFillDirection = false;

    // Timer color settings
    public int timerDefaultColor = 0xFFFFFF00;  // Default yellow
    public int timerTempColor = 0xFFFFFF00;     // Temporary color (for countdown effect)
    public long timerTempEndTimeMs = 0;         // End time in milliseconds for temp color
    public boolean timerColorSwitch = true;     // true = switch mode (no duration), false = countdown mode

    // Visibility
    public boolean visible = false;

    // Current server values (targets for interpolation)
    public float teamAProgress = 0f;
    public int teamAScore = 0;
    public float teamBProgress = 0f;
    public int teamBScore = 0;
    public int timerTicks = 0;

    // Smoothly interpolated progress (rendered values)
    public float displayedTeamAProgress = 0f;
    public float displayedTeamBProgress = 0f;

    // Previous values for glow trigger detection
    public float prevTeamAProgress = 0f;
    public float prevTeamBProgress = 0f;
    public int prevTeamAScore = 0;
    public int prevTeamBScore = 0;

    // Glow effect state
    public long teamAGlowStartTime = -1;
    public long teamBGlowStartTime = -1;
    public static final long GLOW_CHANGE_DURATION_MS = 400;   // Duration for "change" mode flash
    public static final long GLOW_LEADING_DURATION_MS = 600;  // Duration for "leading" mode bar travel

    // Leading effect position (0.0 to 1.0, -1 = inactive)
    public float teamALeadingPos = -1f;
    public float teamBLeadingPos = -1f;

    // Sway
    public float swayOffsetX = 0f;
    public float swayOffsetY = 0f;

    public static ScoreboardOverlayClientData getInstance() {
        return INSTANCE;
    }

    /**
     * Called each client tick to interpolate progress bars smoothly.
     */
    public void tick() {
        if (!visible) return;

        // Smooth interpolation (lerp toward target)
        float lerpSpeed = 0.15f;
        displayedTeamAProgress += (teamAProgress - displayedTeamAProgress) * lerpSpeed;
        displayedTeamBProgress += (teamBProgress - displayedTeamBProgress) * lerpSpeed;

        // Clamp
        displayedTeamAProgress = Math.max(0f, Math.min(1f, displayedTeamAProgress));
        displayedTeamBProgress = Math.max(0f, Math.min(1f, displayedTeamBProgress));
    }

    /**
     * Get the effective timer color (temp color if active, otherwise default).
     */
    public int getEffectiveTimerColor() {
        if (timerColorSwitch) {
            // Switch mode: always use temp color (which is set directly)
            return timerTempColor;
        } else {
            // Countdown mode: use temp color if time hasn't expired, otherwise default
            if (System.currentTimeMillis() < timerTempEndTimeMs) {
                return timerTempColor;
            } else {
                return timerDefaultColor;
            }
        }
    }

    /**
     * Set timer color in switch mode (permanent change).
     */
    public void setTimerColorSwitch(int color) {
        timerColorSwitch = true;
        timerTempColor = color;
        timerDefaultColor = color;
        timerTempEndTimeMs = 0;
    }

    /**
     * Set timer color with countdown (temporary change).
     * @param color The temporary color
     * @param durationTicks How many ticks to show this color
     */
    public void setTimerColorCountdown(int color, int durationTicks) {
        timerColorSwitch = false;
        timerTempColor = color;
        // Convert ticks to milliseconds (1 tick = 50ms)
        timerTempEndTimeMs = System.currentTimeMillis() + (durationTicks * 50L);
    }

    /**
     * Check if glow effect should trigger based on progress changes.
     * "none": No effect
     * "change": Flash entire bar when progress changes (no cooldown)
     * "leading": Moving bar from start to end when progress changes (restarts if triggered again)
     */
    public void checkGlowTrigger(float oldAProgress, float oldBProgress) {
        long now = System.currentTimeMillis();

        if ("none".equals(glowMode)) {
            // No glow effect
            teamAGlowStartTime = -1;
            teamBGlowStartTime = -1;
            teamALeadingPos = -1f;
            teamBLeadingPos = -1f;
            return;
        }

        boolean aProgressChanged = Float.compare(teamAProgress, oldAProgress) != 0;
        boolean bProgressChanged = Float.compare(teamBProgress, oldBProgress) != 0;

        if ("change".equals(glowMode)) {
            // "change" mode: flash entire bar, no cooldown
            if (aProgressChanged) {
                teamAGlowStartTime = now;
            }
            if (bProgressChanged) {
                teamBGlowStartTime = now;
            }
        } else if ("leading".equals(glowMode)) {
            // "leading" mode: start moving bar from beginning, restart if triggered again
            if (aProgressChanged) {
                teamAGlowStartTime = now;
                teamALeadingPos = 0f;
            }
            if (bProgressChanged) {
                teamBGlowStartTime = now;
                teamBLeadingPos = 0f;
            }
        }

        // Update previous values
        prevTeamAProgress = teamAProgress;
        prevTeamBProgress = teamBProgress;
        prevTeamAScore = teamAScore;
        prevTeamBScore = teamBScore;
    }

    /**
     * Get the glow intensity for "change" mode (flash effect).
     * Returns 0.0 to 1.0, where 1.0 is max brightness.
     */
    public float getChangeGlowIntensity(boolean isTeamA) {
        if (!"change".equals(glowMode)) return 0f;

        long startTime = isTeamA ? teamAGlowStartTime : teamBGlowStartTime;
        if (startTime < 0) return 0f;

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > GLOW_CHANGE_DURATION_MS) return 0f;

        // Quick flash then fade out
        float progress = (float) elapsed / GLOW_CHANGE_DURATION_MS;
        return (1f - progress);
    }

    /**
     * Get the leading bar position for "leading" mode (0.0 to 1.0).
     * Returns -1 if inactive.
     */
    public float getLeadingPosition(boolean isTeamA) {
        if (!"leading".equals(glowMode)) return -1f;

        long startTime = isTeamA ? teamAGlowStartTime : teamBGlowStartTime;
        if (startTime < 0) return -1f;

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > GLOW_LEADING_DURATION_MS) {
            // Reset when done
            if (isTeamA) {
                teamALeadingPos = -1f;
            } else {
                teamBLeadingPos = -1f;
            }
            return -1f;
        }

        // Linear movement from 0 to 1
        float pos = (float) elapsed / GLOW_LEADING_DURATION_MS;
        if (isTeamA) {
            teamALeadingPos = pos;
        } else {
            teamBLeadingPos = pos;
        }
        return pos;
    }

    public void reset() {
        teamAIcon = null;
        teamABarColor = 0xFFFF0000;
        teamBIcon = null;
        teamBBarColor = 0xFF0088FF;
        glowMode = "none";
        reverseFillDirection = false;
        timerDefaultColor = 0xFFFFFF00;
        timerTempColor = 0xFFFFFF00;
        timerTempEndTimeMs = 0;
        timerColorSwitch = true;
        visible = false;
        teamAProgress = 0f;
        teamAScore = 0;
        teamBProgress = 0f;
        teamBScore = 0;
        timerTicks = 0;
        displayedTeamAProgress = 0f;
        displayedTeamBProgress = 0f;
        prevTeamAProgress = 0f;
        prevTeamBProgress = 0f;
        prevTeamAScore = 0;
        prevTeamBScore = 0;
        teamAGlowStartTime = -1;
        teamBGlowStartTime = -1;
        teamALeadingPos = -1f;
        teamBLeadingPos = -1f;
    }

    @Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ClientTickHandler {
        private ClientTickHandler() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            INSTANCE.tick();
        }
    }
}
