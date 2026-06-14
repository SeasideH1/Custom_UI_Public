package com.lootmatrix.customui.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KillMessagePacketTest {

    @Test
    void encodeDecodePreservesKillerAndVictimUuid() {
        UUID killerUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID victimUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        KillMessagePacket original = new KillMessagePacket(
                "Killer", "Victim", "customui:textures/overlay/generic.png",
                true, false, true, KillMessagePacket.ICON_TACZ,
                true, false, killerUuid, victimUuid
        );

        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(encoded);

        KillMessagePacket decoded = new KillMessagePacket(encoded);

        assertEquals(killerUuid, decoded.killerUuid);
        assertEquals(victimUuid, decoded.victimUuid);
    }

    @Test
    void encodeDecodePreservesNullKillerUuidForEnvironmentalKills() {
        UUID victimUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        KillMessagePacket original = new KillMessagePacket(
                "", "Victim", "customui:textures/overlay/fall.png",
                false, true, false, KillMessagePacket.ICON_OTHER,
                false, true, null, victimUuid
        );

        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(encoded);

        KillMessagePacket decoded = new KillMessagePacket(encoded);

        assertNull(decoded.killerUuid);
        assertEquals(victimUuid, decoded.victimUuid);
    }
}
