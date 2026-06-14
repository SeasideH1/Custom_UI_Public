package com.lootmatrix.customui.client.metrics;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.network.NetPingPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side performance / network metric collector feeding the STAT HUD
 * elements (value + line chart).
 *
 * <ul>
 *   <li><b>FPS / frame time</b>: measured from render ticks (EMA-smoothed
 *       frame delta, frame count per 250 ms window)</li>
 *   <li><b>RTT / jitter / packet loss / TPS</b>: a tiny echo probe
 *       ({@code NetPingPacket}/{@code NetPongPacket}) every 10 game ticks;
 *       jitter is the RTT standard deviation, loss the timeout ratio over a
 *       sliding window, and the pong carries the server's average tick time</li>
 * </ul>
 *
 * Everything is stored in fixed ring buffers — zero steady-state allocation,
 * readers (the HUD renderer) access the arrays directly.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientMetrics {

    public static final int SRC_FPS = 0;
    public static final int SRC_FRAME_TIME = 1;
    public static final int SRC_PING = 2;
    public static final int SRC_PACKET_LOSS = 3;
    public static final int SRC_JITTER = 4;
    public static final int SRC_TPS = 5;
    public static final int SOURCE_COUNT = 6;

    /** Chart history per source. */
    public static final int CAPACITY = 240;

    private static final float[][] SERIES = new float[SOURCE_COUNT][CAPACITY];
    private static final int[] HEAD = new int[SOURCE_COUNT];
    private static final int[] COUNT = new int[SOURCE_COUNT];
    private static final float[] CURRENT = new float[SOURCE_COUNT];

    // ---- frame timing ----
    private static long lastFrameNanos = 0L;
    private static float frameTimeEmaMs = 16.6f;
    private static int frameCounter = 0;
    private static long fpsWindowStartNanos = 0L;
    private static long lastFrameSeriesPushNanos = 0L;

    // ---- echo probe ----
    private static final int PROBE_INTERVAL_TICKS = 10;
    private static final long PROBE_TIMEOUT_NANOS = 2_500_000_000L;
    /** Sliding outcome window: true = answered, false = lost. */
    private static final int OUTCOME_WINDOW = 40;
    private static final boolean[] OUTCOMES = new boolean[OUTCOME_WINDOW];
    private static int outcomeHead = 0;
    private static int outcomeCount = 0;

    /** In-flight probes (ring; stale entries count as losses). */
    private static final int PENDING_CAP = 16;
    private static final int[] pendingSeq = new int[PENDING_CAP];
    private static final long[] pendingNanos = new long[PENDING_CAP];
    private static final boolean[] pendingUsed = new boolean[PENDING_CAP];

    /** Recent RTTs for jitter (stddev). */
    private static final int RTT_WINDOW = 32;
    private static final float[] RTTS = new float[RTT_WINDOW];
    private static int rttHead = 0;
    private static int rttCount = 0;

    private static int sequenceCounter = 0;
    private static int tickCounter = 0;

    private ClientMetrics() {}

    // ==================== Reader API (render thread) ====================

    public static float current(int source) {
        return CURRENT[source];
    }

    public static float[] seriesData(int source) {
        return SERIES[source];
    }

    public static int seriesHead(int source) {
        return HEAD[source];
    }

    public static int seriesCount(int source) {
        return COUNT[source];
    }

    public static int sourceId(String name) {
        if (name == null) return SRC_FPS;
        return switch (name) {
            case "frame_time", "frametime" -> SRC_FRAME_TIME;
            case "ping", "rtt", "latency" -> SRC_PING;
            case "packet_loss", "loss" -> SRC_PACKET_LOSS;
            case "jitter", "net_jitter" -> SRC_JITTER;
            case "tps" -> SRC_TPS;
            default -> SRC_FPS;
        };
    }

    // ==================== Frame metrics ====================

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            float deltaMs = (now - lastFrameNanos) / 1_000_000f;
            if (deltaMs > 0f && deltaMs < 1000f) {
                frameTimeEmaMs += (deltaMs - frameTimeEmaMs) * 0.10f;
                CURRENT[SRC_FRAME_TIME] = frameTimeEmaMs;
            }
        }
        lastFrameNanos = now;
        frameCounter++;

        if (fpsWindowStartNanos == 0L) {
            fpsWindowStartNanos = now;
        } else if (now - fpsWindowStartNanos >= 250_000_000L) {
            float seconds = (now - fpsWindowStartNanos) / 1_000_000_000f;
            CURRENT[SRC_FPS] = frameCounter / seconds;
            frameCounter = 0;
            fpsWindowStartNanos = now;
        }

        // 4 Hz chart push for the frame-side sources
        if (now - lastFrameSeriesPushNanos >= 250_000_000L) {
            lastFrameSeriesPushNanos = now;
            push(SRC_FPS, CURRENT[SRC_FPS]);
            push(SRC_FRAME_TIME, CURRENT[SRC_FRAME_TIME]);
        }
    }

    // ==================== Network probe ====================

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null || minecraft.player == null) return;
        if (++tickCounter < PROBE_INTERVAL_TICKS) return;
        tickCounter = 0;

        long now = System.nanoTime();
        // Expire stale probes as losses
        for (int i = 0; i < PENDING_CAP; i++) {
            if (pendingUsed[i] && now - pendingNanos[i] > PROBE_TIMEOUT_NANOS) {
                pendingUsed[i] = false;
                recordOutcome(false);
            }
        }

        int slot = freeSlot();
        if (slot < 0) return; // probe burst outstanding; skip this round
        int seq = ++sequenceCounter;
        pendingSeq[slot] = seq;
        pendingNanos[slot] = now;
        pendingUsed[slot] = true;
        ModNetworkHandler.INSTANCE.sendToServer(new NetPingPacket(seq, now));
    }

    /** Called from the packet bridge on the main thread. */
    public static void onPong(int sequence, long clientNanos, float serverTickMs) {
        boolean matched = false;
        for (int i = 0; i < PENDING_CAP; i++) {
            if (pendingUsed[i] && pendingSeq[i] == sequence) {
                pendingUsed[i] = false;
                matched = true;
                break;
            }
        }
        if (!matched) {
            // Late pong: the probe already timed out and was counted as a loss
            // (or the state was reset on logout) — don't double-count it.
            return;
        }
        recordOutcome(true);

        float rtt = (System.nanoTime() - clientNanos) / 1_000_000f;
        if (rtt < 0f || rtt > 10_000f) return;
        CURRENT[SRC_PING] = rtt;
        push(SRC_PING, rtt);

        RTTS[rttHead] = rtt;
        rttHead = (rttHead + 1) % RTT_WINDOW;
        if (rttCount < RTT_WINDOW) rttCount++;
        CURRENT[SRC_JITTER] = rttStdDev();
        push(SRC_JITTER, CURRENT[SRC_JITTER]);

        float tps = serverTickMs > 0f ? Math.min(20f, 1000f / Math.max(serverTickMs, 50f)) : 20f;
        CURRENT[SRC_TPS] = tps;
        push(SRC_TPS, tps);
    }

    private static void recordOutcome(boolean answered) {
        OUTCOMES[outcomeHead] = answered;
        outcomeHead = (outcomeHead + 1) % OUTCOME_WINDOW;
        if (outcomeCount < OUTCOME_WINDOW) outcomeCount++;
        int lost = 0;
        for (int i = 0; i < outcomeCount; i++) {
            if (!OUTCOMES[i]) lost++;
        }
        float lossPercent = outcomeCount > 0 ? lost * 100f / outcomeCount : 0f;
        CURRENT[SRC_PACKET_LOSS] = lossPercent;
        push(SRC_PACKET_LOSS, lossPercent);
    }

    private static float rttStdDev() {
        if (rttCount < 2) return 0f;
        float mean = 0f;
        for (int i = 0; i < rttCount; i++) mean += RTTS[i];
        mean /= rttCount;
        float variance = 0f;
        for (int i = 0; i < rttCount; i++) {
            float dev = RTTS[i] - mean;
            variance += dev * dev;
        }
        return (float) Math.sqrt(variance / rttCount);
    }

    private static int freeSlot() {
        for (int i = 0; i < PENDING_CAP; i++) {
            if (!pendingUsed[i]) return i;
        }
        return -1;
    }

    private static void push(int source, float value) {
        SERIES[source][HEAD[source]] = value;
        HEAD[source] = (HEAD[source] + 1) % CAPACITY;
        if (COUNT[source] < CAPACITY) COUNT[source]++;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        for (int i = 0; i < SOURCE_COUNT; i++) {
            if (i == SRC_FPS || i == SRC_FRAME_TIME) continue; // frame metrics keep running
            HEAD[i] = 0;
            COUNT[i] = 0;
            CURRENT[i] = 0f;
        }
        for (int i = 0; i < PENDING_CAP; i++) pendingUsed[i] = false;
        outcomeHead = 0;
        outcomeCount = 0;
        rttHead = 0;
        rttCount = 0;
        tickCounter = 0;
    }
}
