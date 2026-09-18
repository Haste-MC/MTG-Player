package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DeckStoreTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void speichernListenLaden(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        assertEquals(List.of(), store.names());
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        store.save("Mein Abzan", d);
        assertEquals(List.of("Mein Abzan"), store.names());
        Deck loaded = store.load("Mein Abzan");
        assertEquals(1, loaded.getCommanders().size());
        assertEquals(d.get(forge.deck.DeckSection.Main).countAll(), loaded.get(forge.deck.DeckSection.Main).countAll());
    }

    @Test
    void unbekannterNameWirft(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new DeckStore(dir).load("nix"));
    }

    @Test
    void dateinameWirdBereinigt(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        store.save("A/B: C?", Precons.load("Abzan Armor [TDC] [2025]"));
        assertEquals(List.of("A/B: C?"), store.names(), "Anzeigename bleibt, nur der Dateiname wird bereinigt");
        assertTrue(dir.resolve("A_B_ C_.dck").toFile().exists());
    }
}
