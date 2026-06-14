package com.lootmatrix.customui.network;

import com.lootmatrix.customui.client.ScoreboardIconPresets;
import com.lootmatrix.customui.client.ScoreboardOverlayClientData;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side packet handlers for scoreboard overlay packets.
 * Only called on the client dist via DistExecutor.
 */
public class ScoreboardOverlayClientHandler {

    public static void handleConfig(ScoreboardOverlayConfigPacket pkt) {
        ScoreboardOverlayClientData data = ScoreboardOverlayClientData.getInstance();

        // Use icon presets to resolve icon paths (supports preset names or full paths)
        ScoreboardIconPresets presets = ScoreboardIconPresets.getInstance();
        data.teamAIcon = pkt.teamAIconPath.isEmpty() ? null : presets.resolveIcon(pkt.teamAIconPath);
        data.teamABarColor = pkt.teamABarColor;
        data.teamBIcon = pkt.teamBIconPath.isEmpty() ? null : presets.resolveIcon(pkt.teamBIconPath);
        data.teamBBarColor = pkt.teamBBarColor;
        data.glowMode = pkt.glowMode;
        data.visible = pkt.visible;

        // Timer color settings
        data.timerDefaultColor = pkt.timerColor;
        data.timerTempColor = pkt.timerTempColor;
        data.timerColorSwitch = pkt.timerColorSwitch;
        data.reverseFillDirection = pkt.reverseFillDirection;
        // Convert remaining ticks to end time in milliseconds
        if (!pkt.timerColorSwitch && pkt.timerTempDuration > 0) {
            data.timerTempEndTimeMs = System.currentTimeMillis() + (pkt.timerTempDuration * 50L);
        } else {
            data.timerTempEndTimeMs = 0;
        }
    }

    public static void handleUpdate(ScoreboardOverlayUpdatePacket pkt) {
        ScoreboardOverlayClientData data = ScoreboardOverlayClientData.getInstance();
        float oldA = data.teamAProgress;
        float oldB = data.teamBProgress;

        // Check if this is a full sync (player just joined) - deltaMask 0x1F means all fields
        boolean isFullSync = pkt.deltaMask == 0x1F;

        if ((pkt.deltaMask & 0x01) != 0) data.teamAProgress = pkt.teamAProgress;
        if ((pkt.deltaMask & 0x02) != 0) data.teamAScore = pkt.teamAScore;
        if ((pkt.deltaMask & 0x04) != 0) data.teamBProgress = pkt.teamBProgress;
        if ((pkt.deltaMask & 0x08) != 0) data.teamBScore = pkt.teamBScore;
        if ((pkt.deltaMask & 0x10) != 0) data.timerTicks = pkt.timerTicks;

        // For full sync (player join), also sync displayed progress immediately
        if (isFullSync) {
            data.displayedTeamAProgress = data.teamAProgress;
            data.displayedTeamBProgress = data.teamBProgress;
            data.prevTeamAProgress = data.teamAProgress;
            data.prevTeamBProgress = data.teamBProgress;
            data.prevTeamAScore = data.teamAScore;
            data.prevTeamBScore = data.teamBScore;
        } else {
            // Only check glow trigger for incremental updates
            data.checkGlowTrigger(oldA, oldB);
        }
    }
}
