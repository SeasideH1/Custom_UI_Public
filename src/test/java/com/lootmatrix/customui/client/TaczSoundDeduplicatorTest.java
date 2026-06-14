package com.lootmatrix.customui.client;

import com.lootmatrix.customui.client.sound.TaczSoundDeduplicator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaczSoundDeduplicatorTest {

    @AfterEach
    void clearDeduplicator() {
        TaczSoundDeduplicator.clear();
    }

    @Test
    void cancelsSecondFleshHitForSameEntityInsideBurstWindow() {
        assertFalse(TaczSoundDeduplicator.shouldCancelHitSound(42, false, 1_000));
        assertTrue(TaczSoundDeduplicator.shouldCancelHitSound(42, false, 1_011));
    }

    @Test
    void allowsFleshHitForSameEntityAfterBurstWindow() {
        assertFalse(TaczSoundDeduplicator.shouldCancelHitSound(42, false, 1_000));
        assertFalse(TaczSoundDeduplicator.shouldCancelHitSound(42, false, 1_021));
    }

    @Test
    void allowsHeadshotToReplaceRecentFleshHit() {
        assertFalse(TaczSoundDeduplicator.shouldCancelHitSound(42, false, 1_000));
        assertFalse(TaczSoundDeduplicator.shouldCancelHitSound(42, true, 1_006));
        assertTrue(TaczSoundDeduplicator.shouldCancelHitSound(42, true, 1_007));
    }

    @Test
    void cancelsDuplicateHeadshotInsideBurstWindow() {
        assertFalse(TaczSoundDeduplicator.shouldCancelHitSound(42, true, 1_000));
        assertTrue(TaczSoundDeduplicator.shouldCancelHitSound(42, true, 1_006));
    }

    @Test
    void keepsDirectShootSoundForLocalShooter() {
        assertFalse(TaczSoundDeduplicator.shouldCancelDirectShootSound(7, "shoot", 2_000, true));
    }

    @Test
    void cancelsDuplicateDirectShootSoundForLocalShooterInsideBurstWindow() {
        assertFalse(TaczSoundDeduplicator.shouldCancelDirectShootSound(7, "shoot", 2_000, true));
        assertTrue(TaczSoundDeduplicator.shouldCancelDirectShootSound(7, "shoot", 2_019, true));
    }

    @Test
    void cancelsDirectShootSoundForRemoteShooter() {
        assertTrue(TaczSoundDeduplicator.shouldCancelDirectShootSound(7, "shoot", 2_000, false));
    }

    @Test
    void cancelsMatchingThirdPersonEchoAfterLocalShoot() {
        TaczSoundDeduplicator.markLocalShootSound(7, "shoot", 3_000);

        assertTrue(TaczSoundDeduplicator.shouldCancelEchoedThirdPersonShoot(7, "shoot", 3_034, true));
        assertFalse(TaczSoundDeduplicator.shouldCancelEchoedThirdPersonShoot(7, "shoot", 3_036, true));
    }

    @Test
    void keepsThirdPersonShootForNonLocalShooter() {
        TaczSoundDeduplicator.markLocalShootSound(7, "shoot", 3_000);

        assertFalse(TaczSoundDeduplicator.shouldCancelEchoedThirdPersonShoot(7, "shoot", 3_010, false));
    }

    @Test
    void keepsThirdPersonShootWhenLocalEchoWindowMisses() {
        assertFalse(TaczSoundDeduplicator.shouldCancelEchoedThirdPersonShoot(7, "shoot", 3_010, true));
    }

    @Test
    void keepsThirdPersonSilenceWhenOnlyShootWasMarkedLocally() {
        TaczSoundDeduplicator.markLocalShootSound(7, "shoot", 3_000);

        assertFalse(TaczSoundDeduplicator.shouldCancelEchoedThirdPersonShoot(7, "silence", 3_010, true));
    }

    @Test
    void doesNotDeduplicateDifferentShooters() {
        assertFalse(TaczSoundDeduplicator.shouldCancelHitSound(42, false, 1_000));
        assertFalse(TaczSoundDeduplicator.shouldCancelHitSound(43, false, 1_001));
        TaczSoundDeduplicator.markLocalShootSound(7, "shoot", 2_000);
        assertFalse(TaczSoundDeduplicator.shouldCancelEchoedThirdPersonShoot(8, "shoot", 2_001, true));
    }
}
