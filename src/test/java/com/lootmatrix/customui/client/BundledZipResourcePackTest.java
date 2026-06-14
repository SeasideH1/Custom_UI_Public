package com.lootmatrix.customui.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BundledZipResourcePackTest {

    private static final String ZIP_NAME = "[必需]AiMatrix 必需资源包.zip";

    @Test
    void listResourcesNormalizesBundledSoundPaths() {
        BundledZipResourcePack pack = new BundledZipResourcePack("test", ZIP_NAME);
        Set<String> listedPaths = new LinkedHashSet<>();

        pack.listResources(PackType.CLIENT_RESOURCES, "minecraft", "sounds", (location, supplier) ->
                listedPaths.add(location.getPath()));

        assertTrue(listedPaths.contains("sounds/battlefield/deploy_transition_in_02_01.ogg"));
        assertTrue(listedPaths.contains("sounds/counterstrike/c4_plant.ogg"));
        assertFalse(listedPaths.contains("sounds/Battlefield/Deploy_Transition_In_02_01.ogg"));
    }

    @Test
    void getResourceProvidesSanitizedSoundsJsonAndLowercaseLookup() throws IOException {
        BundledZipResourcePack pack = new BundledZipResourcePack("test", ZIP_NAME);

        IoSupplier<InputStream> soundsJsonSupplier = pack.getResource(
                PackType.CLIENT_RESOURCES,
                ResourceLocation.fromNamespaceAndPath("minecraft", "sounds.json")
        );
        assertNotNull(soundsJsonSupplier);

        String soundsJson;
        try (InputStream inputStream = soundsJsonSupplier.get()) {
            soundsJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(soundsJson.contains("battlefield/deploy_transition_in_02_01"));
        assertTrue(soundsJson.contains("counterstrike/c4_plant"));
        assertFalse(soundsJson.contains("Battlefield/Deploy_Transition_In_02_01"));

        IoSupplier<InputStream> soundSupplier = pack.getResource(
                PackType.CLIENT_RESOURCES,
                ResourceLocation.fromNamespaceAndPath("minecraft", "sounds/battlefield/deploy_transition_in_02_01.ogg")
        );
        assertNotNull(soundSupplier);
    }
}
