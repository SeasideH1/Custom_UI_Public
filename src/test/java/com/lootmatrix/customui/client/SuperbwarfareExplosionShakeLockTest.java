package com.lootmatrix.customui.client;

import com.atsuishio.superbwarfare.config.client.DisplayConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuperbwarfareExplosionShakeLockTest {

    @Test
    void lockedSpecValuesStayAtNinety() {
        assertEquals(90, SuperbwarfareExplosionShakeLock.lockedValue());
        assertEquals(90, SuperbwarfareExplosionShakeLock.minValue());
        assertEquals(90, SuperbwarfareExplosionShakeLock.maxValue());
    }

    @Test
    void targetExplosionShakeConfigAlwaysLocksToNinety() {
        assertTrue(SuperbwarfareExplosionShakeLock.shouldLock(DisplayConfig.EXPLOSION_SCREEN_SHAKE));
        assertEquals(90,
                SuperbwarfareExplosionShakeLock.coerceSet(DisplayConfig.EXPLOSION_SCREEN_SHAKE, Integer.valueOf(0)));
        assertEquals(90,
                SuperbwarfareExplosionShakeLock.coerceSet(DisplayConfig.EXPLOSION_SCREEN_SHAKE, Integer.valueOf(100)));
    }

    @Test
    void unrelatedConfigValuesAreNotChanged() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.IntValue other = builder.defineInRange("other", 25, 0, 100);

        assertFalse(SuperbwarfareExplosionShakeLock.shouldLock(other));
        assertEquals(25, SuperbwarfareExplosionShakeLock.coerceSet(other, Integer.valueOf(25)));
    }
}
