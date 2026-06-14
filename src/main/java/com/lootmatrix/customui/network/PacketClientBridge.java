package com.lootmatrix.customui.network;

import com.lootmatrix.customui.atmosphere.AtmosphereEngine;
import com.lootmatrix.customui.atmosphere.AtmospherePreset;
import com.lootmatrix.customui.cinematic.CameraPath;
import com.lootmatrix.customui.cinematic.CinematicCameraEngine;
import com.lootmatrix.customui.client.CaptureZoneBoundaryRenderer;
import com.lootmatrix.customui.client.CaptureZoneHudRenderer;
import com.lootmatrix.customui.client.KillMessageClientState;
import com.lootmatrix.customui.client.ObjectiveOverlayClientData;
import com.lootmatrix.customui.client.TeamGlowRenderer;
import com.lootmatrix.customui.client.hud.HudClientTemplateCache;
import com.lootmatrix.customui.client.hud.HudEditorScreen;
import com.lootmatrix.customui.client.hud.HudPlaybackManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

/**
 * Client-only bridge for packet handlers. Packet classes call into this bridge
 * reflectively so the server JAR can exclude client implementations entirely.
 */
@OnlyIn(Dist.CLIENT)
public final class PacketClientBridge {

    private PacketClientBridge() {}

    public static void handleDamageNumber(UUID entityUUID, float damage, boolean isKill, boolean isCritical,
                                          boolean isHeadshot, long killEventId) {
        DamageNumberClientHandler.handleDamagePacket(entityUUID, damage, isKill, isCritical, isHeadshot, killEventId);
    }

    public static void handleDamageIndicator(Vec3 sourcePos, float damageAmount) {
        DamageIndicatorClientHandler.handleDamagePacket(sourcePos, damageAmount);
    }

    public static void handleScoreboardConfig(ScoreboardOverlayConfigPacket packet) {
        ScoreboardOverlayClientHandler.handleConfig(packet);
    }

    public static void handleScoreboardUpdate(ScoreboardOverlayUpdatePacket packet) {
        ScoreboardOverlayClientHandler.handleUpdate(packet);
    }

    public static void handleKillMessage(String killerName, String victimName, String weaponIconPath,
                                         boolean killerIsTeammate, boolean victimIsTeammate,
                                         boolean isHeadshot, byte iconType,
                                         boolean killerIsLocalPlayer, boolean victimIsLocalPlayer,
                                         UUID killerUuid, UUID victimUuid) {
        KillMessageClientHandler.handle(
                killerName, victimName, weaponIconPath,
                killerIsTeammate, victimIsTeammate, isHeadshot, iconType,
                killerIsLocalPlayer, victimIsLocalPlayer, killerUuid, victimUuid
        );
    }

    public static void setKillMessageEnabled(boolean enabled) {
        KillMessageClientState.getInstance().setEnabled(enabled);
    }

    public static void setKillMessageMode(String mode) {
        KillMessageClientState.getInstance().setDisplayModeFromString(mode);
    }

    public static void handleTitle(String text, int color, float alpha, float scale,
                                   float offsetX, float offsetY,
                                   long fadeInMs, long stayMs, long fadeOutMs, int line,
                                   int anchorId, int originId) {
        ObjectiveOverlayClientData.getInstance().addTitle(
                new ObjectiveOverlayClientData.TitleDisplay(
                        text, color, alpha, scale, offsetX, offsetY,
                        fadeInMs, stayMs, fadeOutMs, line, anchorId, originId
                )
        );
    }

    public static void handleTitleImage(String iconPath, int size, float alpha,
                                        float offsetX, float offsetY,
                                        long fadeInMs, long stayMs, long fadeOutMs,
                                        int anchorId, int originId) {
        ObjectiveOverlayClientData.getInstance().addImageTitle(
                new ObjectiveOverlayClientData.ImageTitleDisplay(
                        iconPath, size, alpha, offsetX, offsetY,
                        fadeInMs, stayMs, fadeOutMs, anchorId, originId
                )
        );
    }

    public static void setServerGlow(UUID targetId, boolean hasGlow) {
        TeamGlowRenderer.setServerGlow(targetId, hasGlow);
    }

    public static void handleCinematicStart(CameraPath path) {
        CinematicCameraEngine.getInstance().startPath(path);
    }

    public static void handleCinematicStop() {
        CinematicCameraEngine.getInstance().stop();
    }

    public static void handleAtmosphereApply(AtmospherePreset preset) {
        AtmosphereEngine.getInstance().applyPreset(preset);
    }

    public static void handleAtmosphereClear() {
        AtmosphereEngine.getInstance().clearPreset();
    }

    public static void handleCaptureZoneSync(String zoneId, String displayName,
                                              float progress, String capturingTeam,
                                              String ownerTeam, boolean contested) {
        CaptureZoneHudRenderer.getInstance().updateZoneState(
                zoneId, displayName, progress, capturingTeam, ownerTeam, contested);
    }

    public static void handleCaptureZoneGeometry(String zoneId, String displayName,
                                                  double originX, double originY, double originZ,
                                                  List<CaptureZoneGeometrySyncPacket.ShapeData> shapes) {
        CaptureZoneBoundaryRenderer.getInstance().updateGeometry(
                zoneId, displayName, originX, originY, originZ, shapes);
    }

    public static void handleCaptureZoneClearGeometry() {
        CaptureZoneBoundaryRenderer.getInstance().clearAll();
        CaptureZoneHudRenderer.getInstance().clearAll();
    }

    public static void handleHudTemplateSync(boolean reset, List<String> templateJsons) {
        HudClientTemplateCache.applySync(reset, templateJsons);
    }

    public static void handleHudTemplateRemove(String templateId) {
        HudClientTemplateCache.applyRemove(templateId);
    }

    public static void handleHudScoreboardDefine(List<String> keys, int[] values) {
        com.lootmatrix.customui.client.hud.HudScoreboardClientCache.applyDefine(keys, values);
    }

    public static void handleHudScoreboardDelta(int[] indices, int[] values) {
        com.lootmatrix.customui.client.hud.HudScoreboardClientCache.applyDelta(indices, values);
    }

    public static void handleHudEntityDefine(List<String> keys, List<String> values) {
        com.lootmatrix.customui.client.hud.HudEntityClientCache.applyDefine(keys, values);
    }

    public static void handleHudEntityDelta(int[] indices, List<String> values) {
        com.lootmatrix.customui.client.hud.HudEntityClientCache.applyDelta(indices, values);
    }

    public static void handleNetPong(int sequence, long clientNanos, float serverTickMs) {
        com.lootmatrix.customui.client.metrics.ClientMetrics.onPong(sequence, clientNanos, serverTickMs);
    }

    /** Action ordinals follow {@link HudPlayPacket.Action}. */
    public static void handleHudPlay(int action, String templateId, int elapsedTicks) {
        switch (action) {
            case 0 -> HudPlaybackManager.play(templateId, elapsedTicks);
            case 1 -> HudPlaybackManager.seek(templateId, elapsedTicks);
            case 2 -> HudPlaybackManager.stop(templateId);
            case 3 -> HudPlaybackManager.stopAll();
            case 4 -> HudEditorScreen.open(templateId);
            case 5 -> com.lootmatrix.customui.client.hud.GuiTemplateScreen.open(templateId, false);
            case 6 -> com.lootmatrix.customui.client.hud.GuiTemplateScreen.closeFromServer(templateId);
            case 7 -> com.lootmatrix.customui.client.hud.HudKeyBindings.setActivated(templateId, true);
            case 8 -> com.lootmatrix.customui.client.hud.HudKeyBindings.setActivated(templateId, false);
            default -> { }
        }
    }
}
