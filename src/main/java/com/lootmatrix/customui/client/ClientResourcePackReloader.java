package com.lootmatrix.customui.client;

import com.lootmatrix.customui.client.render.RenderResourceCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientResourcePackReloader {

    public static final String REQUIRED_PACK_ID = "customui_required";
    public static final String MEMORYS_CITY_PACK_ID = "customui_required_memorys_city";
    private static final long RELOAD_DEBOUNCE_MS = 4000L;
    private static final String STATE_FILE_NAME = "customui-resource-pack-state.properties";
    private static final String MEMORYS_CITY_DEFAULT_APPLIED_KEY = "memorysCityDefaultApplied";

    private static final AtomicBoolean RELOAD_QUEUED = new AtomicBoolean(false);
    private static final AtomicLong LAST_RELOAD_REQUEST_AT_MS = new AtomicLong(0L);
    private static final AtomicBoolean UNLOCK_RELOAD_COMPLETED = new AtomicBoolean(false);

    private ClientResourcePackReloader() {}

    public static void reloadPreservingState(Runnable onComplete) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        if (minecraft.screen instanceof PackSelectionScreen) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        long now = System.currentTimeMillis();
        long lastRequest = LAST_RELOAD_REQUEST_AT_MS.get();
        if (now - lastRequest < RELOAD_DEBOUNCE_MS) {
            if (onComplete != null) {
                minecraft.execute(onComplete);
            }
            return;
        }
        LAST_RELOAD_REQUEST_AT_MS.set(now);

        if (!RELOAD_QUEUED.compareAndSet(false, true)) {
            return;
        }

        minecraft.execute(() -> {
            try {
                PackRepository repository = minecraft.getResourcePackRepository();
                if (repository == null) {
                    finish(onComplete);
                    return;
                }

                List<String> selectedIds = new ArrayList<>(repository.getSelectedIds());
                repository.reload();

                Collection<String> availableIds = new LinkedHashSet<>(repository.getAvailableIds());
                List<String> restoredIds = buildRestoredSelection(selectedIds, availableIds);

                repository.setSelected(restoredIds);

                CompletableFuture<?> future = minecraft.reloadResourcePacks();
                future.whenComplete((unused, throwable) -> minecraft.execute(() -> finish(onComplete)));
            } catch (Throwable ignored) {
                finish(onComplete);
            }
        });
    }

    public static void applyMemorysCityDefaultSelectionOnce() {
        if (isMemorysCityDefaultSelectionHandled()) {
            return;
        }
        reloadPreservingState(null);
    }

    private static void finish(Runnable onComplete) {
        RELOAD_QUEUED.set(false);
        RenderResourceCache.onResourceReload();
        ClientBootstrap.scheduleUiTexturePrewarm();
        if (onComplete != null) {
            onComplete.run();
        }
    }

    static List<String> buildRestoredSelection(Collection<String> selectedIds, Collection<String> availableIds) {
        return buildRestoredSelection(selectedIds, availableIds, consumeMemorysCityDefaultSelection(availableIds));
    }

    static List<String> buildRestoredSelection(Collection<String> selectedIds, Collection<String> availableIds,
                                               boolean applyMemorysCityDefault) {
        List<String> restoredIds = new ArrayList<>();
        for (String selectedId : selectedIds) {
            if (!availableIds.contains(selectedId)) {
                continue;
            }
            if (REQUIRED_PACK_ID.equals(selectedId)) {
                continue;
            }
            restoredIds.add(selectedId);
        }

        // Forge persists selected pack order, and the UI shows the highest-priority pack at the top.
        // Append the one-time default before AiMatrix so Memorys City starts below the required pack.
        if (applyMemorysCityDefault
                && availableIds.contains(MEMORYS_CITY_PACK_ID)
                && !restoredIds.contains(MEMORYS_CITY_PACK_ID)) {
            restoredIds.add(MEMORYS_CITY_PACK_ID);
        }
        if (availableIds.contains(REQUIRED_PACK_ID)) {
            restoredIds.add(REQUIRED_PACK_ID);
        }
        return restoredIds;
    }

    private static boolean consumeMemorysCityDefaultSelection(Collection<String> availableIds) {
        if (!availableIds.contains(MEMORYS_CITY_PACK_ID)) {
            return false;
        }

        Path statePath = FMLPaths.CONFIGDIR.get().resolve(STATE_FILE_NAME);
        Properties properties = loadState(statePath);
        if (isMemorysCityDefaultSelectionHandled(properties)) {
            return false;
        }

        properties.setProperty(MEMORYS_CITY_DEFAULT_APPLIED_KEY, Boolean.TRUE.toString());
        storeState(statePath, properties);
        return true;
    }

    private static boolean isMemorysCityDefaultSelectionHandled() {
        Path statePath = FMLPaths.CONFIGDIR.get().resolve(STATE_FILE_NAME);
        return isMemorysCityDefaultSelectionHandled(loadState(statePath));
    }

    private static boolean isMemorysCityDefaultSelectionHandled(Properties properties) {
        return Boolean.parseBoolean(properties.getProperty(MEMORYS_CITY_DEFAULT_APPLIED_KEY));
    }

    private static Properties loadState(Path statePath) {
        Properties properties = new Properties();
        if (!Files.exists(statePath)) {
            return properties;
        }

        try (InputStream input = Files.newInputStream(statePath)) {
            properties.load(input);
        } catch (IOException ignored) {
            return new Properties();
        }
        return properties;
    }

    private static void storeState(Path statePath, Properties properties) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(statePath)) {
                properties.store(output, "CustomUI resource pack state");
            }
        } catch (IOException ignored) {
            // If state cannot be persisted, keep this launch usable and try again next time.
        }
    }

    public static void requestUnlockReloadOnce(Runnable onComplete) {
        if (!UNLOCK_RELOAD_COMPLETED.compareAndSet(false, true)) {
            if (onComplete != null) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null) {
                    minecraft.execute(onComplete);
                } else {
                    onComplete.run();
                }
            }
            return;
        }
        reloadPreservingState(onComplete);
    }

    public static void resetUnlockReloadState() {
        UNLOCK_RELOAD_COMPLETED.set(false);
        LAST_RELOAD_REQUEST_AT_MS.set(0L);
        RELOAD_QUEUED.set(false);
    }
}
