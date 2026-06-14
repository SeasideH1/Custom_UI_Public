package com.lootmatrix.customui.client.sound;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public final class TaczSoundDeduplicator {

    private static final long HIT_SOUND_WINDOW_MS = 20L;
    private static final long DIRECT_SHOOT_SOUND_WINDOW_MS = 20L;
    private static final long THIRD_PERSON_ECHO_WINDOW_MS = 35L;
    private static final long STALE_ENTRY_MS = 250L;

    private static final Map<Integer, HitRecord> RECENT_HITS = new HashMap<>();
    private static final Map<SoundKey, Long> RECENT_LOCAL_SHOOT_SOUNDS = new HashMap<>();

    private TaczSoundDeduplicator() {
    }

    public static synchronized boolean shouldCancelHitSound(int entityId, boolean headshot, long nowMs) {
        cleanup(nowMs);

        HitRecord previous = RECENT_HITS.get(entityId);
        if (previous != null && nowMs - previous.timestampMs <= HIT_SOUND_WINDOW_MS) {
            if (!headshot || previous.headshot) {
                return true;
            }
        }

        RECENT_HITS.put(entityId, new HitRecord(nowMs, headshot));
        return false;
    }

    public static synchronized boolean shouldCancelDirectShootSound(int shooterId, String soundKind, long nowMs,
                                                                    boolean localShooter) {
        if (!localShooter) {
            return true;
        }

        cleanup(nowMs);

        SoundKey key = new SoundKey(shooterId, soundKind);
        Long previous = RECENT_LOCAL_SHOOT_SOUNDS.get(key);
        if (previous != null && nowMs - previous <= DIRECT_SHOOT_SOUND_WINDOW_MS) {
            return true;
        }

        RECENT_LOCAL_SHOOT_SOUNDS.put(key, nowMs);
        return false;
    }

    public static synchronized void markLocalShootSound(int shooterId, String soundKind, long nowMs) {
        cleanup(nowMs);
        RECENT_LOCAL_SHOOT_SOUNDS.put(new SoundKey(shooterId, soundKind), nowMs);
    }

    public static synchronized boolean shouldCancelEchoedThirdPersonShoot(int shooterId, String soundKind, long nowMs,
                                                                          boolean localShooter) {
        if (!localShooter) {
            return false;
        }

        cleanup(nowMs);

        Long previous = RECENT_LOCAL_SHOOT_SOUNDS.get(new SoundKey(shooterId, soundKind));
        return previous != null && nowMs - previous <= THIRD_PERSON_ECHO_WINDOW_MS;
    }

    public static synchronized void clear() {
        RECENT_HITS.clear();
        RECENT_LOCAL_SHOOT_SOUNDS.clear();
    }

    private static void cleanup(long nowMs) {
        RECENT_HITS.entrySet().removeIf(entry -> nowMs - entry.getValue().timestampMs > STALE_ENTRY_MS);
        removeStale(RECENT_LOCAL_SHOOT_SOUNDS, nowMs);
    }

    private static void removeStale(Map<SoundKey, Long> timestamps, long nowMs) {
        Iterator<Map.Entry<SoundKey, Long>> iterator = timestamps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SoundKey, Long> entry = iterator.next();
            if (nowMs - entry.getValue() > STALE_ENTRY_MS) {
                iterator.remove();
            }
        }
    }

    private record HitRecord(long timestampMs, boolean headshot) {
    }

    private record SoundKey(int entityId, String soundKind) {
        private SoundKey {
            soundKind = Objects.requireNonNull(soundKind, "soundKind");
        }
    }
}
