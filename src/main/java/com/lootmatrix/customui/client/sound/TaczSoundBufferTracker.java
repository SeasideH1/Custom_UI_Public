package com.lootmatrix.customui.client.sound;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class TaczSoundBufferTracker {

    private static final Map<Channel, SoundBuffer> CHANNEL_BUFFERS = new ConcurrentHashMap<>();

    private TaczSoundBufferTracker() {
    }

    public static void track(Channel channel, SoundBuffer buffer) {
        if (channel == null || buffer == null) {
            return;
        }
        CHANNEL_BUFFERS.put(channel, buffer);
    }

    public static void release(Channel channel) {
        SoundBuffer buffer = CHANNEL_BUFFERS.remove(channel);
        if (buffer != null) {
            buffer.discardAlBuffer();
        }
    }
}