package com.lootmatrix.customui.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NetworkPacketDecodeTest {

    @Test
    void guiInteractPacketClampsNegativeActionOrdinal() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(-1);
        buf.writeUtf("customui:test_gui", 256);
        buf.writeUtf("button", 256);
        buf.writeBoolean(true);

        assertDoesNotThrow(() -> new GuiInteractPacket(buf));
    }

    @Test
    void uiWidgetActionPacketClampsNegativeActionOrdinal() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf("customui:test_ui", 256);
        buf.writeUtf("button", 256);
        buf.writeByte(-1);
        buf.writeUtf("payload", 1024);

        assertDoesNotThrow(() -> new UIWidgetActionPacket(buf));
    }
}
