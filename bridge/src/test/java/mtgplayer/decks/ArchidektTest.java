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
import java.util.ArrayList;
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
    void listeFiltertCommanderUndFolgtNext() throws IOException {
        String p1 = Files.readString(Path.of("src/test/resources/archidekt-list-1.json"));
        String p2 = Files.readString(Path.of("src/test/resources/archidekt-list-2.json"));
        List<String> urls = new ArrayList<>();
        Archidekt a = new Archidekt(url -> { urls.add(url); return url.contains("page=2") ? p2 : p1; });
        List<Archidekt.Entry> decks = a.listDecks("plssssss");
        assertEquals(List.of(26595870L, 26566111L, 9592911L), decks.stream().map(Archidekt.Entry::id).toList());
        assertEquals("https://card-images.archidekt.com/art/front/5/f/5feba5d6-99a6-4e9b-8a7d-90d955868fc3.webp?1783911263", decks.get(0).art());
        assertEquals("https://card-images.archidekt.com/art/front/r/i/rin.webp", decks.get(2).art(), "customFeatured leer -> featured");
        assertEquals("HOBBITS CREATE MONSTERS", decks.get(0).name());
        assertEquals("2026-09-21T18:41:17.869883Z", decks.get(0).updatedAt());
        assertTrue(urls.get(0).contains("ownerUsername=plssssss"), urls.get(0));
        assertEquals(2, urls.size());
    }

    @Test
    void listeFehlerWirdGemeldet() {
        Archidekt a = new Archidekt(url -> { throw new IOException("HTTP 404"); });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> a.listDecks("x"));
        assertTrue(e.getMessage().startsWith("Archidekt:"));
        assertThrows(IllegalArgumentException.class, () -> new Archidekt(url -> "{}").listDecks("  "), "leerer Name");
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> new Archidekt(url -> "kein json").listDecks("x"));
        assertTrue(bad.getMessage().startsWith("Archidekt:"));
    }

    @Test
    void fetchLiefertUpdatedAt() throws IOException {
        String body = Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        assertEquals("2018-03-12T05:12:58Z", new Archidekt(url -> body).fetch("1").updatedAt());
    }

    @Test
    @EnabledIfSystemProperty(named = "mtgplayer.net", matches = "true")
    void rauchtestMitEchtemNetz() {
        Archidekt.Result r = Archidekt.standard().fetch("1");
        assertEquals("Fun With Fungus", r.name());
    }
}
