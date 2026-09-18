package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** resolve() + save() (save laeuft in Bridge normalerweise erst nach ALLEN resolve()-Aufrufen). */
    private static Deck resolveAndSave(DeckSource src, JsonNode node) {
        DeckSource.Resolved r = src.resolve(node);
        r.save().run();
        return r.deck();
    }

    @Test
    void precon(@TempDir Path dir) {
        Deck d = resolveAndSave(new DeckSource(new DeckStore(dir)), Json.parse("{\"precon\":\"Abzan Armor [TDC] [2025]\"}"));
        assertEquals(1, d.getCommanders().size());
    }

    @Test
    void textWirdGeparstUndGespeichert(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n2 Forest\\n";
        Deck d = resolveAndSave(new DeckSource(store), Json.parse("{\"text\":\"" + text + "\",\"deckName\":\"Testdeck\"}"));
        assertEquals("Felothar the Steadfast", d.getCommanders().get(0).getName());
        assertEquals(List.of("Testdeck"), store.names());
        Deck again = resolveAndSave(new DeckSource(store), Json.parse("{\"saved\":\"Testdeck\"}"));
        assertEquals(1, again.getCommanders().size());
    }

    @Test
    void textOhneNamenNimmtVorschlag(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        resolveAndSave(new DeckSource(store), Json.parse("{\"text\":\"1 Sol Ring\\n1 Felothar the Steadfast\\n\"}"));
        assertEquals(List.of("Felothar the Steadfast"), store.names());
    }

    @Test
    void deckNameUeberschreibtSpielernameBeimSpeichern(@TempDir Path dir) {
        // Regression: startGame.opponents[i].name ist der Spielername ("KI 1"), nicht der
        // Speichername des Decks - der kommt aus dem eigenen Feld "deckName".
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n2 Forest\\n";
        Deck d = resolveAndSave(new DeckSource(store),
                Json.parse("{\"text\":\"" + text + "\",\"name\":\"KI 1\",\"deckName\":\"Mein Deck\"}"));
        assertEquals("Felothar the Steadfast", d.getCommanders().get(0).getName());
        assertEquals(List.of("Mein Deck"), store.names());
    }

    @Test
    void ohneDeckNameWirdSpielernameNichtAlsSpeichernameGenutzt(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n";
        resolveAndSave(new DeckSource(store), Json.parse("{\"text\":\"" + text + "\",\"name\":\"KI 1\"}"));
        assertEquals(List.of("Felothar the Steadfast"), store.names());
    }

    @Test
    void speichertErstNachAllenResolveAufrufen(@TempDir Path dir) {
        // resolve() alleine darf noch nichts auf Platte schreiben - erst save().run().
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n";
        DeckSource.Resolved r = new DeckSource(store).resolve(Json.parse("{\"text\":\"" + text + "\",\"deckName\":\"Spaeter\"}"));
        assertEquals(List.of(), store.names());
        r.save().run();
        assertEquals(List.of("Spaeter"), store.names());
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
