package com.lootmatrix.customui.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageNumberPacketTest {

    @Test
    void encodeDecodePreservesCriticalHeadshotAndKillEventId() {
        UUID entityId = UUID.fromString("12345678-1234-5678-9abc-123456789abc");
        DamageNumberPacket original = new DamageNumberPacket(entityId, 12.5f, true, false, true, 42L);

        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(encoded);

        DamageNumberPacket decoded = new DamageNumberPacket(encoded);

        assertEquals(entityId, decoded.getEntityUUID());
        assertEquals(12.5f, decoded.getDamage());
        assertTrue(decoded.isKill());
        assertFalse(decoded.isCritical());
        assertTrue(decoded.isHeadshot());
        assertEquals(42L, decoded.getKillEventId());
    }
}
