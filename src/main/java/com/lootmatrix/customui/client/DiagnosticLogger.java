package com.lootmatrix.customui.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic tool to capture what happens when the game freezes after backgrounding.
 * Writes to a file in the run directory for post-crash analysis.
 *
 * Call DiagnosticLogger.pulse("YourClassName") at the start of render/tick methods.
 * If a freeze is detected, recent history is written to customui_freeze_log.txt.
 */
@OnlyIn(Dist.CLIENT)
public final class DiagnosticLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomUI-Diag");

    private DiagnosticLogger() {}

    // Ring buffer for recent events
    private static final int BUFFER_SIZE = 200;
    private static final String[] eventBuffer = new String[BUFFER_SIZE];
    private static final long[] timeBuffer = new long[BUFFER_SIZE];
    private static final AtomicInteger bufferIndex = new AtomicInteger(0);

    // Timing
    private static final AtomicLong lastPulseNs = new AtomicLong(0);
    private static final AtomicLong maxGapNs = new AtomicLong(0);

    // Detection
    private static final long FREEZE_THRESHOLD_NS = 500_000_000L; // 500ms
    private static volatile boolean freezeDetected = false;
    private static volatile boolean logWritten = false;

    // Delta times and thread info
    private static final long[] deltaBuffer = new long[BUFFER_SIZE];
    private static final String[] threadBuffer = new String[BUFFER_SIZE];

    /**
     * Call at the start of each render/tick handler.
     * Records the event and checks for freeze.
     */
    public static void pulse(String source) {
        long now = System.nanoTime();
        long last = lastPulseNs.getAndSet(now);

        long delta = last > 0 ? (now - last) : 0;

        int idx = bufferIndex.getAndIncrement() % BUFFER_SIZE;
        eventBuffer[idx] = source;
        timeBuffer[idx] = now;
        deltaBuffer[idx] = delta;
        threadBuffer[idx] = Thread.currentThread().getName();

        if (last > 0) {
            long gap = now - last;

            // Track max gap
            long currentMax = maxGapNs.get();
            while (gap > currentMax) {
                if (maxGapNs.compareAndSet(currentMax, gap)) break;
                currentMax = maxGapNs.get();
            }

            // Detect freeze
            if (gap > FREEZE_THRESHOLD_NS && !freezeDetected) {
                freezeDetected = true;
                LOGGER.debug("[CustomUI-Diag] Freeze detected: {}ms from {}", gap / 1_000_000, source);
                writeLog(gap, source);
            }
        }
    }

    /**
     * Write diagnostic log to file.
     */
    private static void writeLog(long freezeGapNs, String triggerSource) {
        if (logWritten) return;
        logWritten = true;

        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            String filename = "customui_freeze_log_" + timestamp + ".txt";

            try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
                writer.println("=== CustomUI Freeze Diagnostic Log ===");
                writer.println("Timestamp: " + new Date());
                writer.println("Freeze gap: " + (freezeGapNs / 1_000_000) + "ms");
                writer.println("Trigger source: " + triggerSource);
                writer.println("Max gap seen: " + (maxGapNs.get() / 1_000_000) + "ms");
                writer.println();

                Runtime rt = Runtime.getRuntime();
                writer.println("=== Memory ===");
                writer.println("Used (MB): " + ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)));
                writer.println("Total (MB): " + (rt.totalMemory() / (1024 * 1024)));
                writer.println("Max (MB): " + (rt.maxMemory() / (1024 * 1024)));
                writer.println();

                writer.println("=== Recent Event History (newest first) ===");
                int currentIdx = bufferIndex.get();
                for (int i = 0; i < BUFFER_SIZE; i++) {
                    int idx = (currentIdx - 1 - i + BUFFER_SIZE * 2) % BUFFER_SIZE;
                    String event = eventBuffer[idx];
                    long time = timeBuffer[idx];
                    long delta = deltaBuffer[idx];
                    String thread = threadBuffer[idx];
                    if (event != null) {
                        writer.printf("[%d] %s | %s | +%dms @ %d%n",
                                i, event, thread, (delta / 1_000_000), time);
                    }
                }

                writer.println();
                writer.println("=== All Thread Dumps ===");
                for (var entry : Thread.getAllStackTraces().entrySet()) {
                    Thread t = entry.getKey();
                    writer.println("\n--- Thread: " + t.getName() + " (" + t.getState() + ") ---");
                    for (StackTraceElement ste : entry.getValue()) {
                        writer.println("  " + ste);
                    }
                }
            }

            LOGGER.debug("[CustomUI-Diag] Freeze log written to: {}", filename);
        } catch (Exception e) {
            // Silently fail - diagnostics should not cause issues
        }
    }

    /**
     * Reset detection state (call on world unload or similar).
     */
    public static void reset() {
        freezeDetected = false;
        logWritten = false;
        maxGapNs.set(0);
    }
}
