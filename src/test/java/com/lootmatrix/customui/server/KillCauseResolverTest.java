package com.lootmatrix.customui.server;

import com.lootmatrix.customui.network.KillMessagePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KillCauseResolverTest {

    @Test
    void explosionDamageWinsOverHeldTaczGun() {
        KillCauseResolver.Result result = KillCauseResolver.resolve(
                "explosion.player",
                null,
                "tacz:textures/gun/hud/ak47.png",
                null,
                false
        );

        assertEquals(KillMessagePacket.ICON_OTHER, result.type());
        assertEquals("customui:textures/overlay/explosion.png", result.path());
    }

    @Test
    void recentTaczHitUsesTaczGunIconWhenDamageSourceIsNotExplicitlyOther() {
        KillCauseResolver.Result result = KillCauseResolver.resolve(
                "mob",
                null,
                "tacz:textures/gun/hud/ak47.png",
                null,
                true
        );

        assertEquals(KillMessagePacket.ICON_TACZ, result.type());
        assertEquals("tacz:textures/gun/hud/ak47.png", result.path());
    }

    @Test
    void playerDamageWithoutRecentTaczHitIsMeleeEvenWhenHoldingTaczGun() {
        KillCauseResolver.Result result = KillCauseResolver.resolve(
                "player",
                null,
                "tacz:textures/gun/hud/ak47.png",
                null,
                false
        );

        assertEquals(KillMessagePacket.ICON_MELEE, result.type());
        assertEquals("customui:textures/overlay/melee.png", result.path());
    }

    @Test
    void grenadeLikeDirectEntityUsesExplosionIcon() {
        KillCauseResolver.Result result = KillCauseResolver.resolve(
                "generic",
                "superbwarfare:hand_grenade",
                "tacz:textures/gun/hud/ak47.png",
                null,
                false
        );

        assertEquals(KillMessagePacket.ICON_OTHER, result.type());
        assertEquals("customui:textures/overlay/explosion.png", result.path());
    }
}
