package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class DeckResyncTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void resyncHoltNeuUndBehaeltNamenUndTag(@TempDir Path dir) throws Exception {
        String body = Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        DeckSource src = new DeckSource(store, new Archidekt(url -> body));
        src.resolve(Json.parse("{\"archidekt\":\"https://archidekt.com/decks/1/x\",\"deckName\":\"Pilze\"}")).save().run();
        // zweiter Fetch liefert dieselbe Liste mit einer Karte mehr (Text ersetzt) – hier reicht: Fetch wird gerufen
        int[] calls = {0};
        DeckSource src2 = new DeckSource(store, new Archidekt(url -> { calls[0]++; return body; }));
        DeckSource.Resolved r = src2.resync("Pilze");
        r.save().run();
        assertEquals(1, calls[0]);
        assertEquals(List.of("Pilze"), store.names());
        assertEquals("1", store.archidektId("Pilze"));
        assertEquals("Thelon of Havenwood", r.deck().getCommanders().get(0).getName());
    }

    @Test
    void resyncOhneTagMeldetFehler(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        store.save("Lokal", Precons.load("Abzan Armor [TDC] [2025]"));
        DeckSource src = new DeckSource(store, new Archidekt(url -> { throw new AssertionError("kein Fetch"); }));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> src.resync("Lokal"));
        assertTrue(e.getMessage().startsWith("Resync Lokal:"));
    }
}
