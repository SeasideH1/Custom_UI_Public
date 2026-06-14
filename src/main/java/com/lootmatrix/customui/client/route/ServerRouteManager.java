package com.lootmatrix.customui.client.route;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lootmatrix.customui.Main;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multi-route server connections: each saved server may define alternate
 * addresses ("线路"). Every candidate (primary + alternates) is probed with a
 * few TCP connects measuring connect RTT, timeout ratio (≈ packet loss) and
 * RTT spread (jitter); the combined score
 * {@code avgMs + loss% * 50 + jitter * 0.5} picks the route actually used
 * when the player hits Join.
 *
 * Persisted to {@code config/customui-server-routes.json}, keyed by the
 * server's primary address so vanilla {@code servers.dat} stays untouched.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ServerRouteManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerRouteManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "customui-server-routes.json";

    private static final int SAMPLES_PER_ROUTE = 5;
    /** Gap between samples so the probe is a real window, not one burst. */
    private static final int SAMPLE_SPACING_MS = 250;
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int IO_TIMEOUT_MS = 2000;
    /** 1.20.1; status servers accept any value here. */
    private static final int PROTOCOL_VERSION = 763;
    /** Stats older than this are considered stale for auto-selection. */
    private static final long STATS_FRESH_MILLIS = 10 * 60 * 1000L;

    /** Saved route lists, keyed by primary server address. */
    private static final Map<String, RouteEntry> ENTRIES = new LinkedHashMap<>();
    /** Probe results keyed by route address. */
    private static final Map<String, RouteStats> STATS = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CustomUI-RoutePinger");
        thread.setDaemon(true);
        return thread;
    });

    private static boolean loaded = false;

    private ServerRouteManager() {}

    /** Alternate routes + auto-select flag for one saved server. */
    public static final class RouteEntry {
        public final List<String> routes = new ArrayList<>();
        public boolean autoSelect = true;
    }

    /** Aggregated probe result for one route address. */
    public static final class RouteStats {
        public volatile float averageMs = -1f;
        public volatile float lossPercent = 100f;
        public volatile float jitterMs = 0f;
        public volatile long measuredAt = 0L;
        public volatile boolean probing = false;

        public boolean hasResult() {
            return measuredAt > 0L;
        }

        public float score() {
            if (!hasResult()) return Float.MAX_VALUE;
            if (lossPercent >= 99.9f) return Float.MAX_VALUE; // unreachable
            float avg = averageMs < 0f ? 2000f : averageMs;
            return avg + lossPercent * 50f + jitterMs * 0.5f;
        }
    }

    // ==================== Store ====================

    public static synchronized RouteEntry entryFor(String primaryAddress) {
        ensureLoaded();
        return ENTRIES.computeIfAbsent(normalize(primaryAddress), key -> new RouteEntry());
    }

    @Nullable
    public static synchronized RouteEntry existingEntry(String primaryAddress) {
        ensureLoaded();
        return ENTRIES.get(normalize(primaryAddress));
    }

    public static synchronized void save() {
        ensureLoaded();
        JsonObject root = new JsonObject();
        for (Map.Entry<String, RouteEntry> entry : ENTRIES.entrySet()) {
            if (entry.getValue().routes.isEmpty()) continue;
            JsonObject server = new JsonObject();
            server.addProperty("auto", entry.getValue().autoSelect);
            JsonArray routes = new JsonArray();
            for (String route : entry.getValue().routes) routes.add(route);
            server.add("routes", routes);
            root.add(entry.getKey(), server);
        }
        try {
            Files.writeString(filePath(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.error("[CustomUI] Failed to save server routes", exception);
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = filePath();
        if (!Files.exists(path)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject server = entry.getValue().getAsJsonObject();
                RouteEntry routeEntry = new RouteEntry();
                routeEntry.autoSelect = !server.has("auto") || server.get("auto").getAsBoolean();
                if (server.has("routes") && server.get("routes").isJsonArray()) {
                    for (JsonElement route : server.getAsJsonArray("routes")) {
                        String address = normalize(route.getAsString());
                        if (!address.isEmpty() && !routeEntry.routes.contains(address)) {
                            routeEntry.routes.add(address);
                        }
                    }
                }
                ENTRIES.put(normalize(entry.getKey()), routeEntry);
            }
        } catch (Exception exception) {
            LOGGER.error("[CustomUI] Failed to read server routes", exception);
        }
    }

    private static Path filePath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    public static String normalize(String address) {
        return address == null ? "" : address.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ==================== Selection ====================

    /** Primary + alternates, primary first. */
    public static List<String> candidates(String primaryAddress) {
        String primary = normalize(primaryAddress);
        List<String> result = new ArrayList<>(4);
        result.add(primary);
        RouteEntry entry = existingEntry(primary);
        if (entry != null) {
            for (String route : entry.routes) {
                if (!result.contains(route)) result.add(route);
            }
        }
        return result;
    }

    /**
     * Best-scoring fresh candidate for a server, or null when auto-select is
     * off, no alternates exist or no fresh measurements are available.
     */
    @Nullable
    public static String bestRoute(String primaryAddress) {
        RouteEntry entry = existingEntry(primaryAddress);
        if (entry == null || !entry.autoSelect || entry.routes.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        String best = null;
        float bestScore = Float.MAX_VALUE;
        for (String candidate : candidates(primaryAddress)) {
            RouteStats stats = STATS.get(candidate);
            if (stats == null || !stats.hasResult() || now - stats.measuredAt > STATS_FRESH_MILLIS) {
                continue;
            }
            float score = stats.score();
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public static RouteStats statsFor(String address) {
        return STATS.computeIfAbsent(normalize(address), key -> new RouteStats());
    }

    // ==================== Probing ====================

    /** Queues async probes for every candidate of a server. */
    public static void probeServer(String primaryAddress) {
        for (String candidate : candidates(primaryAddress)) {
            probeRoute(candidate);
        }
    }

    /** Queues an async probe (no-op while one is already running). */
    public static void probeRoute(String address) {
        String normalized = normalize(address);
        if (normalized.isEmpty()) return;
        RouteStats stats = statsFor(normalized);
        if (stats.probing) return;
        stats.probing = true;
        EXECUTOR.submit(() -> {
            try {
                probeBlocking(normalized, stats);
            } finally {
                stats.probing = false;
            }
        });
    }

    /**
     * N spaced application-level pings: average RTT, timeout ratio, spread.
     * Each sample is a full server-list ping (handshake → status → ping/pong)
     * and measures the pong round trip — a bare TCP SYN to a local
     * accelerator/proxy would report ~0 ms, the protocol pong cannot.
     */
    private static void probeBlocking(String address, RouteStats stats) {
        String host = address;
        int port = 25565;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && colon < address.length() - 1) {
            try {
                port = Integer.parseInt(address.substring(colon + 1));
                host = address.substring(0, colon);
            } catch (NumberFormatException ignored) {
                host = address;
            }
        }

        int failures = 0;
        float sum = 0f;
        int ok = 0;
        float[] samples = new float[SAMPLES_PER_ROUTE];
        for (int i = 0; i < SAMPLES_PER_ROUTE; i++) {
            float ms = sampleStatusPing(host, port);
            if (ms < 0f) {
                failures++;
            } else {
                samples[ok] = ms;
                sum += ms;
                ok++;
            }
            if (i < SAMPLES_PER_ROUTE - 1) {
                try {
                    Thread.sleep(SAMPLE_SPACING_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (ok > 0) {
            float avg = sum / ok;
            float variance = 0f;
            for (int i = 0; i < ok; i++) {
                float dev = samples[i] - avg;
                variance += dev * dev;
            }
            stats.averageMs = avg;
            stats.jitterMs = (float) Math.sqrt(variance / ok);
        } else {
            stats.averageMs = -1f;
            stats.jitterMs = 0f;
        }
        stats.lossPercent = failures * 100f / SAMPLES_PER_ROUTE;
        stats.measuredAt = System.currentTimeMillis();
    }

    /**
     * One server-list ping; returns the ping/pong RTT in ms or -1 on failure.
     */
    private static float sampleStatusPing(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(IO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(socket.getOutputStream()));
            java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());

            // Handshake (next state = status)
            java.io.ByteArrayOutputStream handshakeBytes = new java.io.ByteArrayOutputStream(64);
            java.io.DataOutputStream handshake = new java.io.DataOutputStream(handshakeBytes);
            handshake.writeByte(0x00);
            writeVarInt(handshake, PROTOCOL_VERSION);
            byte[] hostUtf = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, hostUtf.length);
            handshake.write(hostUtf);
            handshake.writeShort(port);
            writeVarInt(handshake, 1);
            writeVarInt(out, handshakeBytes.size());
            handshakeBytes.writeTo(out);

            // Status request + consume the JSON response
            out.writeByte(0x01);
            out.writeByte(0x00);
            out.flush();
            skipFully(in, readVarInt(in));

            // Ping / pong: the measured round trip
            long start = System.nanoTime();
            out.writeByte(0x09);
            out.writeByte(0x01);
            out.writeLong(start);
            out.flush();
            skipFully(in, readVarInt(in));
            return (System.nanoTime() - start) / 1_000_000f;
        } catch (Exception exception) {
            return -1f;
        }
    }

    private static void writeVarInt(java.io.DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(java.io.DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 35) {
                throw new IOException("VarInt too long");
            }
        }
    }

    private static void skipFully(java.io.DataInputStream in, int count) throws IOException {
        int remaining = count;
        while (remaining > 0) {
            int skipped = (int) in.skip(remaining);
            if (skipped <= 0) {
                in.readUnsignedByte();
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    /** Refresh measurements for every stored multi-route server in the background. */
    public static synchronized void probeAllStored() {
        ensureLoaded();
        for (Map.Entry<String, RouteEntry> entry : ENTRIES.entrySet()) {
            if (!entry.getValue().routes.isEmpty()) {
                probeServer(entry.getKey());
            }
        }
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        // Fresh stats are ready by the time the player picks a server
        if (event.getNewScreen() instanceof JoinMultiplayerScreen) {
            probeAllStored();
        }
    }
}
