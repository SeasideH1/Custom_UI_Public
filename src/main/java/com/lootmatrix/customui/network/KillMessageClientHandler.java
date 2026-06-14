package com.lootmatrix.customui.network;

import com.lootmatrix.customui.client.KillMessageOverlayRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Client-side handler for KillMessagePacket.
 * Separated to prevent class loading issues on the server.
 */
@OnlyIn(Dist.CLIENT)
public class KillMessageClientHandler {

    public static void handle(String killerName, String victimName, String weaponIconPath,
                               boolean killerIsTeammate, boolean victimIsTeammate,
                               boolean isHeadshot, byte iconType,
                               boolean killerIsLocalPlayer, boolean victimIsLocalPlayer,
                               UUID killerUuid, UUID victimUuid) {
        KillMessageOverlayRenderer.getInstance().addKillMessage(
                killerName, victimName, weaponIconPath,
                killerIsTeammate, victimIsTeammate, isHeadshot, iconType,
                killerIsLocalPlayer, victimIsLocalPlayer, killerUuid, victimUuid
        );
    }
}

