package com.lootmatrix.customui.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientResourcePackReloaderTest {

    @Test
    void buildRestoredSelectionAppendsRequiredPackWhenNewlyDiscovered() {
        List<String> restored = ClientResourcePackReloader.buildRestoredSelection(
                List.of("vanilla", "file/custom"),
                List.of("vanilla", "file/custom", ClientResourcePackReloader.REQUIRED_PACK_ID),
                false
        );

        assertEquals(List.of("vanilla", "file/custom", ClientResourcePackReloader.REQUIRED_PACK_ID), restored);
    }

    @Test
    void buildRestoredSelectionPreservesExistingRequiredPackPosition() {
        List<String> restored = ClientResourcePackReloader.buildRestoredSelection(
                List.of("vanilla", ClientResourcePackReloader.REQUIRED_PACK_ID, "file/custom"),
                List.of("vanilla", ClientResourcePackReloader.REQUIRED_PACK_ID, "file/custom"),
                false
        );

        assertEquals(List.of("vanilla", "file/custom", ClientResourcePackReloader.REQUIRED_PACK_ID), restored);
    }

    @Test
    void buildRestoredSelectionDefaultSelectsMemorysCityBelowAimatrixOnce() {
        List<String> restored = ClientResourcePackReloader.buildRestoredSelection(
                List.of("vanilla", "file/custom"),
                List.of("vanilla", "file/custom",
                        ClientResourcePackReloader.MEMORYS_CITY_PACK_ID,
                        ClientResourcePackReloader.REQUIRED_PACK_ID),
                true
        );

        assertEquals(List.of("vanilla", "file/custom",
                ClientResourcePackReloader.MEMORYS_CITY_PACK_ID,
                ClientResourcePackReloader.REQUIRED_PACK_ID), restored);
    }

    @Test
    void buildRestoredSelectionDoesNotReaddMemorysCityAfterDefaultHandled() {
        List<String> restored = ClientResourcePackReloader.buildRestoredSelection(
                List.of("vanilla", "file/custom"),
                List.of("vanilla", "file/custom",
                        ClientResourcePackReloader.MEMORYS_CITY_PACK_ID,
                        ClientResourcePackReloader.REQUIRED_PACK_ID),
                false
        );

        assertEquals(List.of("vanilla", "file/custom", ClientResourcePackReloader.REQUIRED_PACK_ID), restored);
    }

    @Test
    void buildRestoredSelectionPreservesSelectedMemorysCityPosition() {
        List<String> restored = ClientResourcePackReloader.buildRestoredSelection(
                List.of("vanilla",
                        ClientResourcePackReloader.MEMORYS_CITY_PACK_ID,
                        "file/custom",
                        ClientResourcePackReloader.REQUIRED_PACK_ID),
                List.of("vanilla", "file/custom",
                        ClientResourcePackReloader.MEMORYS_CITY_PACK_ID,
                        ClientResourcePackReloader.REQUIRED_PACK_ID),
                false
        );

        assertEquals(List.of("vanilla",
                ClientResourcePackReloader.MEMORYS_CITY_PACK_ID,
                "file/custom",
                ClientResourcePackReloader.REQUIRED_PACK_ID), restored);
    }

    @Test
    void buildRestoredSelectionDropsUnavailableIdsAndKeepsOtherOrder() {
        List<String> restored = ClientResourcePackReloader.buildRestoredSelection(
                List.of("file/a", "missing", "file/b"),
                List.of("file/a", "file/b",
                        ClientResourcePackReloader.MEMORYS_CITY_PACK_ID,
                        ClientResourcePackReloader.REQUIRED_PACK_ID),
                false
        );

        assertEquals(List.of("file/a", "file/b", ClientResourcePackReloader.REQUIRED_PACK_ID), restored);
    }
}
