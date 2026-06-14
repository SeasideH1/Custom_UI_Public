package com.lootmatrix.customui.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Collection;
import java.util.Queue;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight cache for frequently resolved render resources.
 * Keeps dynamic UI texture parsing off the per-frame hot path.
 */
public final class RenderResourceCache {

    private static final Map<String, ResourceLocation> RESOURCE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> INVALID_RESOURCE_CACHE = ConcurrentHashMap.newKeySet();
    private static final Map<ResourceLocation, Boolean> EXISTENCE_CACHE = new ConcurrentHashMap<>();
    private static final Queue<ResourceLocation> PREWARM_QUEUE = new ArrayDeque<>();
    private static final Set<ResourceLocation> PREWARM_PENDING = new HashSet<>();

    private RenderResourceCache() {
    }

    @Nullable
    public static ResourceLocation get(@Nullable String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        if (INVALID_RESOURCE_CACHE.contains(location)) {
            return null;
        }

        ResourceLocation cached = RESOURCE_CACHE.get(location);
        if (cached != null) {
            return cached;
        }

        ResourceLocation parsed = ResourceLocation.tryParse(location);
        if (parsed == null) {
            INVALID_RESOURCE_CACHE.add(location);
            return null;
        }

        ResourceLocation existing = RESOURCE_CACHE.putIfAbsent(location, parsed);
        return existing != null ? existing : parsed;
    }

    public static ResourceLocation getOrCreate(String namespace, String path) {
        String key = namespace + ":" + path;
        ResourceLocation cached = RESOURCE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        ResourceLocation parsed = ResourceLocation.fromNamespaceAndPath(namespace, path);
        ResourceLocation existing = RESOURCE_CACHE.putIfAbsent(key, parsed);
        return existing != null ? existing : parsed;
    }

    public static boolean exists(@Nullable ResourceLocation location) {
        if (location == null) {
            return false;
        }

        Boolean cached = EXISTENCE_CACHE.get(location);
        if (cached != null) {
            return cached;
        }

        boolean exists = false;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            exists = minecraft != null && minecraft.getResourceManager().getResource(location).isPresent();
        } catch (Exception ignored) {
        }
        EXISTENCE_CACHE.put(location, exists);
        return exists;
    }

    public static void prewarmTextures(Collection<ResourceLocation> resources) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || resources == null || resources.isEmpty()) {
            return;
        }

        for (ResourceLocation resource : resources) {
            if (resource == null) {
                continue;
            }
            exists(resource);
            minecraft.getTextureManager().getTexture(resource);
        }
    }

    public static void scheduleTexturePrewarm(Collection<ResourceLocation> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }

        for (ResourceLocation resource : resources) {
            if (resource == null || PREWARM_PENDING.contains(resource)) {
                continue;
            }
            PREWARM_PENDING.add(resource);
            PREWARM_QUEUE.add(resource);
        }
    }

    public static void prewarmScheduledTextures(int limit) {
        if (limit <= 0 || PREWARM_QUEUE.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        for (int i = 0; i < limit; i++) {
            ResourceLocation resource = PREWARM_QUEUE.poll();
            if (resource == null) {
                // Queue fully drained — invalidate existence cache so that
                // resources that became available after pack reload are
                // no longer cached as missing.
                clearExistenceCache();
                return;
            }

            PREWARM_PENDING.remove(resource);
            minecraft.getTextureManager().getTexture(resource);
        }
    }

    public static void clearExistenceCache() {
        EXISTENCE_CACHE.clear();
    }

    public static void onResourceReload() {
        clearExistenceCache();
        PREWARM_QUEUE.clear();
        PREWARM_PENDING.clear();
    }
}
