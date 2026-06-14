package com.lootmatrix.customui.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protects against crashes and freezes when the game is backgrounded.
 *
 * Problem: Windows Efficiency Mode + NVIDIA drivers can cause nvapi64.dll crashes
 * when the game resumes from background. This happens during glfwSwapBuffers.
 *
 * Solution: Use multiple detection methods:
 * 1. Window focus state (Minecraft.isWindowActive)
 * 2. Time gap detection (detect OS-level process suspension)
 * 3. Recovery cooldown after regaining focus
 */
@OnlyIn(Dist.CLIENT)
public final class BackgroundGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomUI-Guard");

    private BackgroundGuard() {}

    // ========== Configuration ==========

    /** Gap threshold to detect OS suspension (e.g., Efficiency Mode freeze).
     *  Set high (2s) so normal Alt-Tab never triggers this — only real OS-level process freezes. */
    private static final long SUSPEND_GAP_NS = 2_000_000_000L; // 2s

    /** Cooldown after window regains focus.
     *  Nearly zero — VBO reuse eliminates GL crash risk on resume. */
    private static final long FOCUS_RECOVERY_NS = 1_000_000L; // 1ms (effectively instant)

    /** Cooldown after detecting OS suspension.
     *  Only triggers on genuine 2s+ freezes; keep short since GL state is stable. */
    private static final long SUSPEND_RECOVERY_NS = 10_000_000L; // 10ms

    // ========== State ==========

    private static long lastCallNs = 0;
    private static long skipUntilNs = 0;
    private static boolean wasWindowActive = true;
    private static boolean loggedCooldown = false;

    /**
     * Call at the start of every custom renderer/tick handler.
     * Returns true if the handler should skip this frame.
     */
    public static boolean shouldSkip() {
        // Use a single timestamp per call to avoid inconsistent comparisons.
        final long now = System.nanoTime();

        // Check window focus
        Minecraft mc = Minecraft.getInstance();
        boolean isActive = mc != null && mc.isWindowActive();

        // Detect focus loss -> gain transition
        if (!wasWindowActive && isActive) {
            // Window just regained focus - enter cooldown
            long newSkipUntil = now + FOCUS_RECOVERY_NS;
            if (newSkipUntil > skipUntilNs) {
                skipUntilNs = newSkipUntil;
                if (!loggedCooldown) {
                    LOGGER.debug("[CustomUI] Window regained focus, cooldown {}ms", FOCUS_RECOVERY_NS / 1_000_000);
                    loggedCooldown = true;
                }
            }
        }
        wasWindowActive = isActive;

        // Detect OS-level suspension via time gap
        if (lastCallNs > 0) {
            long gap = now - lastCallNs;
            if (gap > SUSPEND_GAP_NS) {
                // Detected suspend. Do NOT sleep on the render thread; just enter a longer cooldown.
                long newSkipUntil = now + SUSPEND_RECOVERY_NS;
                if (newSkipUntil > skipUntilNs) {
                    skipUntilNs = newSkipUntil;
                    LOGGER.warn("[CustomUI] Detected suspend (gap={}ms), cooldown {}ms",
                            gap / 1_000_000, SUSPEND_RECOVERY_NS / 1_000_000);
                    loggedCooldown = true;
                }
            }
        }
        lastCallNs = now;

        // Skip if window not active
        if (!isActive) {
            return true;
        }

        // Skip if in cooldown
        if (now < skipUntilNs) {
            return true;
        }

        // Reset log flag when cooldown ends
        if (loggedCooldown) {
            loggedCooldown = false;
        }

        return false;
    }

    /**
     * Lightweight check for mixins - doesn't update state.
     */
    public static boolean isInCooldown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isWindowActive()) {
            return true;
        }
        return System.nanoTime() < skipUntilNs;
    }

    /**
     * Reset state (call on disconnect/world unload).
     */
    public static void reset() {
        lastCallNs = 0;
        skipUntilNs = 0;
        wasWindowActive = true;
        loggedCooldown = false;
    }
}
