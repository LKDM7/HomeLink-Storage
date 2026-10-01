package fr.lkdm.homelink.storage.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** JEI and REI information pages: family resolution and complete EN/FR texts. */
class ViewerInfoTest {
    private static JsonObject lang(String language) throws IOException {
        var stream = ViewerInfoTest.class.getResourceAsStream("/assets/homelink_storage/lang/" + language + ".json");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Set<String> infoKeys(JsonObject lang) {
        Set<String> keys = new TreeSet<>();
        for (String key : lang.keySet()) if (key.startsWith("info.homelink_storage.")) keys.add(key);
        return keys;
    }

    @Test void variantsFallBackToTheirFamilyPage() {
        assertEquals(List.of("info.homelink_storage.battery_2", "info.homelink_storage.battery"), ViewerInfo.keys("battery_2"));
        assertEquals(List.of("info.homelink_storage.mining_head_iii", "info.homelink_storage.mining_head"), ViewerInfo.keys("mining_head_iii"));
        assertEquals("info.homelink_storage.copper_pipe", ViewerInfo.keys("waxed_weathered_copper_pipe").getLast());
        assertEquals(List.of("info.homelink_storage.task_display_large", "info.homelink_storage.task_display"), ViewerInfo.keys("task_display_large"));
        assertEquals(List.of("info.homelink_storage.storage_link"), ViewerInfo.keys("storage_link"));
    }

    @Test void everyPageExistsInEnglishAndFrench() throws IOException {
        var english = lang("en_us");
        var french = lang("fr_fr");
        assertFalse(infoKeys(english).isEmpty(), "No information page");
        assertEquals(infoKeys(english), infoKeys(french));
        for (String key : infoKeys(english)) {
            assertFalse(english.get(key).getAsString().isBlank(), key);
            assertFalse(french.get(key).getAsString().isBlank(), key);
        }
    }

    @Test void everyPageNamesAnItemOrAFamily() throws IOException {
        var english = lang("en_us");
        for (String key : infoKeys(english)) {
            String name = key.substring("info.homelink_storage.".length());
            boolean named = english.keySet().stream().anyMatch(other -> (other.startsWith("item.homelink_storage.") || other.startsWith("block.homelink_storage."))
                    && ViewerInfo.keys(other.substring(other.indexOf("homelink_storage.") + "homelink_storage.".length())).contains(key));
            assertTrue(named, "Page without item: " + name);
        }
    }
}
