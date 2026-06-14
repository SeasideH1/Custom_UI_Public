package com.lootmatrix.customui.hud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HudTemplateRegistryWorldReloadTest {

    @TempDir
    Path templateDir;

    @Test
    void reloadWorldTemplateDirectoryPicksUpFilesAddedAfterInitialLoad() throws IOException {
        HudTemplateRegistry registry = new HudTemplateRegistry();

        registry.loadFromWorldDirectory(templateDir);
        assertNull(registry.get("customui:hot_added"));

        Files.writeString(templateDir.resolve("customui.hot_added.json"), """
                {"format":2,"id":"customui:hot_added","elements":[]}
                """, StandardCharsets.UTF_8);

        registry.loadFromWorldDirectory(templateDir);

        assertNotNull(registry.get("customui:hot_added"));
    }
}
