package com.lootmatrix.customui.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.ResourceLocationException;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BundledZipResourcePack implements PackResources {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    private final String packId;
    private final String zipResourcePath;
    private final Set<String> namespaces;
    /** All ZIP entries cached in memory (sanitized path → raw bytes). */
    private final Map<String, byte[]> entryCache;

    public BundledZipResourcePack(String packId, String zipResourcePath) {
        this.packId = packId;
        this.zipResourcePath = zipResourcePath;

        Map<String, byte[]> cache = new LinkedHashMap<>();
        Set<String> ns = new LinkedHashSet<>();
        loadAllEntries(cache, ns);
        this.entryCache = Collections.unmodifiableMap(cache);
        this.namespaces = Collections.unmodifiableSet(ns);
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... pathSegments) {
        String path = String.join("/", pathSegments);
        return readEntry(path);
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        if (packType != PackType.CLIENT_RESOURCES) {
            return null;
        }
        return readEntry("assets/" + location.getNamespace() + "/" + location.getPath());
    }

    @Override
    public void listResources(PackType packType, String namespace, String path, ResourceOutput output) {
        if (packType != PackType.CLIENT_RESOURCES || !namespaces.contains(namespace)) {
            return;
        }

        String normalizedPath = path == null ? "" : path;
        String prefix = "assets/" + namespace + "/";

        for (Map.Entry<String, byte[]> cached : entryCache.entrySet()) {
            String name = cached.getKey();
            if (!name.startsWith(prefix)) {
                continue;
            }

            String relativePath = name.substring(prefix.length());
            if (!normalizedPath.isEmpty() && !relativePath.startsWith(normalizedPath)) {
                continue;
            }

            byte[] bytes = maybeSanitizeResourceBytes(relativePath, cached.getValue());
            final byte[] resourceBytes = bytes;

            try {
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath(namespace, relativePath);
                output.accept(location, () -> new ByteArrayInputStream(resourceBytes));
            } catch (ResourceLocationException e) {
                LOGGER.warn("Skipping invalid bundled resource entry {}", name);
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType packType) {
        return packType == PackType.CLIENT_RESOURCES ? namespaces : Collections.emptySet();
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        IoSupplier<InputStream> supplier = getRootResource("pack.mcmeta");
        if (supplier == null) {
            return null;
        }

        try (InputStream inputStream = supplier.get()) {
            return AbstractPackResources.getMetadataFromStream(serializer, inputStream);
        }
    }

    @Override
    public String packId() {
        return packId;
    }

    @Override
    public void close() {
    }

    /**
     * Read every entry from the bundled ZIP into memory in a single pass.
     * Also collects namespaces. This replaces the old scanNamespaces() + per-call
     * readEntry() pattern that re-opened the ZIP stream on every resource request.
     */
    private void loadAllEntries(Map<String, byte[]> cache, Set<String> namespaceOut) {
        try (InputStream raw = BundledZipResourcePack.class.getClassLoader().getResourceAsStream(zipResourcePath)) {
            if (raw == null) {
                LOGGER.warn("Missing bundled resource pack zip: {}", zipResourcePath);
                return;
            }

            try (ZipInputStream zipStream = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zipStream.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String name = sanitizeZipEntryName(entry.getName());
                    byte[] bytes = readCurrentEntry(zipStream);
                    cache.put(name, bytes);

                    if (name.startsWith("assets/")) {
                        int nsStart = "assets/".length();
                        int nsEnd = name.indexOf('/', nsStart);
                        if (nsEnd > nsStart) {
                            namespaceOut.add(name.substring(nsStart, nsEnd));
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to load bundled resource pack zip: {}", zipResourcePath, e);
        }
    }

    /**
     * Look up a single entry from the in-memory cache. O(1) HashMap get
     * instead of the previous full ZIP stream scan.
     */
    private IoSupplier<InputStream> readEntry(String targetPath) {
        byte[] bytes = entryCache.get(targetPath);
        if (bytes == null) {
            // Try sanitized form
            String sanitized = sanitizeZipEntryName(targetPath);
            bytes = entryCache.get(sanitized);
        }
        if (bytes == null) {
            return null;
        }
        byte[] data = maybeSanitizeResourceBytes(extractRelativeAssetPath(targetPath), bytes);
        return () -> new ByteArrayInputStream(data);
    }

    private static byte[] readCurrentEntry(ZipInputStream zipStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zipStream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sanitizeZipEntryName(String entryName) {
        if (!entryName.startsWith("assets/")) {
            return entryName;
        }

        int namespaceStart = "assets/".length();
        int namespaceEnd = entryName.indexOf('/', namespaceStart);
        if (namespaceEnd <= namespaceStart) {
            return entryName;
        }

        String namespace = entryName.substring(namespaceStart, namespaceEnd).toLowerCase(Locale.ROOT);
        String relativePath = entryName.substring(namespaceEnd + 1);
        return "assets/" + namespace + "/" + sanitizeResourcePath(relativePath);
    }

    private static String sanitizeResourcePath(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private static String extractRelativeAssetPath(String targetPath) {
        if (!targetPath.startsWith("assets/")) {
            return targetPath;
        }

        int namespaceStart = "assets/".length();
        int namespaceEnd = targetPath.indexOf('/', namespaceStart);
        if (namespaceEnd <= namespaceStart || namespaceEnd + 1 >= targetPath.length()) {
            return targetPath;
        }
        return targetPath.substring(namespaceEnd + 1);
    }

    private static byte[] maybeSanitizeResourceBytes(String relativePath, byte[] bytes) {
        if (!"sounds.json".equals(relativePath)) {
            return bytes;
        }

        JsonObject root = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        for (var entry : root.entrySet()) {
            JsonElement eventElement = entry.getValue();
            if (!eventElement.isJsonObject()) {
                continue;
            }

            JsonArray sounds = eventElement.getAsJsonObject().getAsJsonArray("sounds");
            if (sounds == null) {
                continue;
            }

            for (int i = 0; i < sounds.size(); i++) {
                JsonElement soundElement = sounds.get(i);
                if (soundElement.isJsonPrimitive() && soundElement.getAsJsonPrimitive().isString()) {
                    sounds.set(i, GSON.toJsonTree(soundElement.getAsString().toLowerCase(Locale.ROOT)));
                    continue;
                }

                if (soundElement.isJsonObject()) {
                    JsonObject soundObject = soundElement.getAsJsonObject();
                    JsonElement nameElement = soundObject.get("name");
                    if (nameElement != null && nameElement.isJsonPrimitive() && nameElement.getAsJsonPrimitive().isString()) {
                        soundObject.addProperty("name", nameElement.getAsString().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        return GSON.toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
