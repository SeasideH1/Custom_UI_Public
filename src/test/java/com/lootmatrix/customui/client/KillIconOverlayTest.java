package com.lootmatrix.customui.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillIconOverlayTest {

    @BeforeEach
    void resetIndicators() {
        KillIconOverlay.clearIndicatorsForTest();
    }

    @Test
    void sameKillEventIdCreatesOnlyOneIndicator() {
        KillIconOverlay.addKillIndicatorForKillEventForTest(false, 100L);
        KillIconOverlay.addKillIndicatorForKillEventForTest(false, 100L);

        assertEquals(1, KillIconOverlay.indicatorCountForTest());
        assertFalse(KillIconOverlay.isIndicatorHeadshotForTest(100L));
    }

    @Test
    void sameKillEventIdCanUpgradeExistingIndicatorToHeadshot() {
        KillIconOverlay.addKillIndicatorForKillEventForTest(false, 101L);
        KillIconOverlay.addKillIndicatorForKillEventForTest(true, 101L);

        assertEquals(1, KillIconOverlay.indicatorCountForTest());
        assertTrue(KillIconOverlay.isIndicatorHeadshotForTest(101L));
    }

    @Test
    void differentKillEventIdsCreateSeparateIndicators() {
        KillIconOverlay.addKillIndicatorForKillEventForTest(false, 102L);
        KillIconOverlay.addKillIndicatorForKillEventForTest(false, 103L);

        assertEquals(2, KillIconOverlay.indicatorCountForTest());
    }
}
