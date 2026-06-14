package com.lootmatrix.customui.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaczKnockbackHandlerTest {

    @Test
    void doesNotCancelNonTaczKnockbackWithoutEnchant() {
        assertFalse(TaczKnockbackHandler.shouldCancelKnockback(-1.0D, false));
    }

    @Test
    void cancelsZeroStrengthTaczKnockbackWithoutEnchant() {
        assertTrue(TaczKnockbackHandler.shouldCancelKnockback(0.0D, false));
    }

    @Test
    void doesNotCancelPositiveTaczKnockbackWithoutEnchant() {
        assertFalse(TaczKnockbackHandler.shouldCancelKnockback(0.75D, false));
    }

    @Test
    void cancelsPositiveTaczKnockbackWithEnchant() {
        assertTrue(TaczKnockbackHandler.shouldCancelKnockback(0.75D, true));
    }

    @Test
    void doesNotCancelNonTaczKnockbackWithEnchant() {
        assertFalse(TaczKnockbackHandler.shouldCancelKnockback(-1.0D, true));
    }
}
