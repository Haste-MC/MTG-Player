package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ArchidektTest {

    private static JsonNode sample() throws IOException {
        return Json.parse(Files.readString(Path.of("src/test/resources/archidekt-1.json"), StandardCharsets.UTF_8));
    }

    @Test
    void deckIdAusUrlFormen() {
        assertEquals(12345L, Archidekt.parseDeckId("https://archidekt.com/decks/12345/mein-deck"));
        assertEquals(12345L, Archidekt.parseDeckId("https://www.archidekt.com/decks/12345"));
        assertEquals(12345L, Archidekt.parseDeckId("https://archidekt.com/api/decks/12345/"));
        assertEquals(12345L, Archidekt.parseDeckId(" 12345 "));
        assertThrows(IllegalArgumentException.class, () -> Archidekt.parseDeckId("https://moxfield.com/decks/abc"));
        assertThrows(IllegalArgumentException.class, () -> Archidekt.parseDeckId(""));
    }

    @Test
    void beispielDeckWirdZurTextliste() throws IOException {
        Archidekt.Result r = Archidekt.toTextList(sample());
        assertEquals("Fun With Fungus", r.name());
        List<String> lines = r.text().lines().toList();
        assertEquals("Commander", lines.get(0));
        assertEquals("1 Thelon of Havenwood (tsp) 227", lines.get(1));
        assertTrue(lines.contains("Main"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("1 Westvale Abbey // Ormendahl, Profane Prince (")));
        assertEquals(17, lines.stream().filter(l -> Character.isDigit(l.charAt(0))).mapToInt(l -> Integer.parseInt(l.split(" ")[0])).sum());
    }

    @Test
    void maybeboardWirdWeggelassenUndSideboardErkannt() {
        String json = """
            {"name":"T","categories":[
               {"name":"Commander","includedInDeck":true,"isPremier":true},
               {"name":"Maybeboard","includedInDeck":false,"isPremier":false},
               {"name":"Sideboard","includedInDeck":true,"isPremier":false}],
             "cards":[
               {"quantity":1,"categories":["Commander"],"card":{"oracleCard":{"name":"A"},"edition":{"editioncode":"xyz"},"collectorNumber":"1"}},
               {"quantity":2,"categories":["Maybeboard"],"card":{"oracleCard":{"name":"B"},"edition":{"editioncode":"xyz"},"collectorNumber":"2"}},
               {"quantity":3,"categories":["Sideboard"],"card":{"oracleCard":{"name":"C"},"edition":{"editioncode":"xyz"},"collectorNumber":"3"}},
               {"quantity":4,"categories":null,"card":{"oracleCard":{"name":"D"},"edition":{},"collectorNumber":null}}]}
            """;
        Archidekt.Result r = Archidekt.toTextList(Json.parse(json));
        String t = r.text();
        assertTrue(t.contains("Commander\n1 A (xyz) 1"));
        assertFalse(t.contains("B"));
        assertTrue(t.contains("Sideboard\n3 C (xyz) 3"));
        assertTrue(t.contains("Main\n4 D\n") || t.contains("Main\n4 D"));
    }

    @Test
    void fetchNutztDenFetcherUndMeldetFehler() throws IOException {
        String body = Files.readString(Path.of("src/test/resources/archidekt-1.json"), StandardCharsets.UTF_8);
        List<String> urls = new java.util.ArrayList<>();
        Archidekt a = new Archidekt(url -> { urls.add(url); return body; });
        Archidekt.Result r = a.fetch("https://archidekt.com/decks/1/fun-with-fungus");
        assertEquals(List.of("https://archidekt.com/api/decks/1/"), urls);
        assertEquals("Fun With Fungus", r.name());

        Archidekt broken = new Archidekt(url -> { throw new IOException("HTTP 404"); });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> broken.fetch("1"));
        assertTrue(e.getMessage().contains("404"));
    }

    @Test
    @EnabledIfSystemProperty(named = "mtgplayer.net", matches = "true")
    void rauchtestMitEchtemNetz() {
        Archidekt.Result r = Archidekt.standard().fetch("1");
        assertEquals("Fun With Fungus", r.name());
    }
}
