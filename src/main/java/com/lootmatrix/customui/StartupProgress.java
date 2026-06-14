package com.lootmatrix.customui;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Detailed startup progress feed.
 *
 * Every message goes to three sinks:
 * - the log file / console ([CustomUI|Startup] lines),
 * - the FML early loading window (via StartupNotificationManager),
 * - an in-memory ring buffer that the themed {@code CustomLoadingOverlay}
 *   renders as a scrolling load log.
 *
 * Common-side safe: on a dedicated server the early window simply does not
 * exist and messages only reach the console.
 */
public final class StartupProgress {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CAPACITY = 64;
    private static final String[] MESSAGES = new String[CAPACITY];
    private static final long[] TIMESTAMPS = new long[CAPACITY];
    private static int head = 0;
    private static int size = 0;
    /** Monotonic count of every message ever logged (drives progressive reveal). */
    private static long total = 0;

    private StartupProgress() {}

    public static void log(String message) {
        LOGGER.info("[CustomUI|Startup] {}", message);
        try {
            net.minecraftforge.fml.loading.progress.StartupNotificationManager
                    .addModMessage("CustomUI > " + message);
        } catch (Throwable ignored) {
            // Early window provider not available in this environment (e.g. some hybrid servers)
        }
        synchronized (MESSAGES) {
            int index = (head + size) % CAPACITY;
            if (size == CAPACITY) {
                head = (head + 1) % CAPACITY;
            } else {
                size++;
            }
            MESSAGES[index] = message;
            TIMESTAMPS[index] = System.currentTimeMillis();
            total++;
        }
    }

    /** Total number of messages ever logged (monotonic, never wraps). */
    public static long totalLogged() {
        synchronized (MESSAGES) {
            return total;
        }
    }

    /**
     * Copies the newest entries (oldest first) into the supplied arrays.
     * Returns the number of entries written. Allocation-free for render use.
     */
    public static int copyRecent(String[] outMessages, long[] outTimestamps) {
        synchronized (MESSAGES) {
            int n = Math.min(size, outMessages.length);
            for (int i = 0; i < n; i++) {
                int index = (head + size - n + i) % CAPACITY;
                outMessages[i] = MESSAGES[index];
                outTimestamps[i] = TIMESTAMPS[index];
            }
            return n;
        }
    }
}
