package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DeckSourceTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void precon(@TempDir Path dir) {
        Deck d = new DeckSource(new DeckStore(dir)).resolve(Json.parse("{\"precon\":\"Abzan Armor [TDC] [2025]\"}"));
        assertEquals(1, d.getCommanders().size());
    }

    @Test
    void textWirdGeparstUndGespeichert(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n2 Forest\\n";
        Deck d = new DeckSource(store).resolve(Json.parse("{\"text\":\"" + text + "\",\"name\":\"Testdeck\"}"));
        assertEquals("Felothar the Steadfast", d.getCommanders().get(0).getName());
        assertEquals(List.of("Testdeck"), store.names());
        Deck again = new DeckSource(store).resolve(Json.parse("{\"saved\":\"Testdeck\"}"));
        assertEquals(1, again.getCommanders().size());
    }

    @Test
    void textOhneNamenNimmtVorschlag(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        new DeckSource(store).resolve(Json.parse("{\"text\":\"1 Sol Ring\\n1 Felothar the Steadfast\\n\"}"));
        assertEquals(List.of("Felothar the Steadfast"), store.names());
    }

    @Test
    void importProblemeWerdenGemeldetUndNichtGespeichert(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DeckSource(store).resolve(Json.parse("{\"text\":\"1 Sol Ring\\n1 Gibtsnicht\\n\"}")));
        assertTrue(e.getMessage().contains("Gibtsnicht"));
        assertTrue(e.getMessage().toLowerCase().contains("commander"));
        assertEquals(List.of(), store.names());
    }

    @Test
    void ohneBekannteFormWirft(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new DeckSource(new DeckStore(dir)).resolve(Json.parse("{}")));
    }
}
