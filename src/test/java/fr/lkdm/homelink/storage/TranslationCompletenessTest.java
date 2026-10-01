package fr.lkdm.homelink.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Every text the player can see exists in English and French, with matching placeholders. */
class TranslationCompletenessTest {
    private static final Path LANG = Path.of(System.getProperty("homelink_storage.projectDir", "."))
            .resolve("src/main/resources/assets/homelink_storage/lang");

    private static JsonObject load(String language) throws IOException {
        return JsonParser.parseString(Files.readString(LANG.resolve(language + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static long placeholders(String text) {
        return text.chars().filter(c -> c == '%').count();
    }

    @Test void languagesShareKeysAndPlaceholders() throws IOException {
        var english = load("en_us");
        var french = load("fr_fr");
        assertEquals(english.keySet(), french.keySet());
        for (String key : english.keySet()) {
            String en = english.get(key).getAsString(), fr = french.get(key).getAsString();
            assertFalse(en.isBlank() || fr.isBlank(), "Empty translation: " + key);
            assertEquals(placeholders(en), placeholders(fr), "Placeholder mismatch: " + key);
        }
    }
}
