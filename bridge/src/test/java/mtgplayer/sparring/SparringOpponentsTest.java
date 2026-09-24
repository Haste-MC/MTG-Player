package mtgplayer.sparring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gegnerwahl nach Spec §2 (docs/superpowers/specs/2026-09-24-sparring-design.md): gleicher Bracket,
 * bei weniger als drei Kandidaten Erweiterung auf ±1, Abbruch ohne Kandidaten, und ein Deck ohne
 * Bracket spielt gegen Decks ohne Bracket.
 */
class SparringOpponentsTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /**
     * Legt je Eintrag {@code "<Name>=<Bracket>"} ein gespeichertes Deck an; {@code "null"} als Bracket
     * heisst "kein bracket:-Tag". Alle Decks teilen dieselbe Kartenliste - fuer die Gegnerwahl zaehlen
     * nur Name und Tag.
     */
    private static DeckStore storeWith(Path dir, String... spec) {
        DeckStore store = new DeckStore(dir);
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        for (String s : spec) {
            String[] p = s.split("=", 2);
            d.getTags().clear();
            if (!"null".equals(p[1])) {
                d.getTags().add(DeckStore.BRACKET_TAG + p[1]);
            }
            store.save(p[0], d);
        }
        return store;
    }

    @Test
    void abDreiKandidatenBleibtEsBeimGleichenBracket(@TempDir Path dir) {
        DeckStore store = storeWith(dir, "Mein Deck=3", "Gegner A=3", "Gegner B=3", "Gegner C=3", "Weit weg=5");

        assertEquals(List.of("Gegner A", "Gegner B", "Gegner C"), SparringOpponents.candidates(store, "Mein Deck"));
    }

    @Test
    void wenigerAlsDreiErweitertAufBracketPlusMinusEins(@TempDir Path dir) {
        DeckStore store = storeWith(dir, "Mein Deck=3", "Gleich=3", "Drunter=2", "Drueber=4", "Weit weg=5");

        assertEquals(List.of("Drueber", "Drunter", "Gleich"), SparringOpponents.candidates(store, "Mein Deck"));
    }

    @Test
    void ohneKandidatenImBracketWirdAbgebrochen(@TempDir Path dir) {
        DeckStore store = storeWith(dir, "Mein Deck=3", "Weit weg=5", "Ohne Bracket=null");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SparringOpponents.candidates(store, "Mein Deck"));
        assertTrue(e.getMessage().contains("keine Gegner im Bracket 3"), e.getMessage());
        assertTrue(e.getMessage().contains("Bracket setzen oder Decks importieren"), e.getMessage());
    }

    @Test
    void deckOhneBracketSpieltGegenDecksOhneBracket(@TempDir Path dir) {
        DeckStore store = storeWith(dir, "Mein Deck=null", "Auch ohne=null", "Mit Bracket=3");

        assertEquals(List.of("Auch ohne"), SparringOpponents.candidates(store, "Mein Deck"));
    }

    @Test
    void deckOhneBracketOhneKandidatenNenntDenGrund(@TempDir Path dir) {
        DeckStore store = storeWith(dir, "Mein Deck=null", "Mit Bracket=3");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SparringOpponents.candidates(store, "Mein Deck"));
        assertTrue(e.getMessage().contains("ohne Bracket"), e.getMessage());
        assertTrue(e.getMessage().contains("Bracket setzen oder Decks importieren"), e.getMessage());
    }

    @Test
    void unbekanntesDeckWirft(@TempDir Path dir) {
        DeckStore store = storeWith(dir, "Mein Deck=3");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SparringOpponents.candidates(store, "gibt es nicht"));
        assertTrue(e.getMessage().contains("unbekanntes Deck"), e.getMessage());
    }

    /** Gleichverteilt mit Zuruecklegen: fester Seed = feste Folge, Wiederholungen ausdruecklich erlaubt. */
    @Test
    void ziehtJePartieEinenGegnerMitZuruecklegen() {
        List<String> candidates = List.of("A", "B", "C");

        List<String> first = SparringOpponents.draw(candidates, 8, new Random(42));
        List<String> again = SparringOpponents.draw(candidates, 8, new Random(42));

        assertEquals(8, first.size());
        assertEquals(first, again, "gleicher Seed = gleiche Folge");
        assertTrue(candidates.containsAll(first), first.toString());
        Set<String> distinct = new HashSet<>(first);
        assertTrue(distinct.size() < first.size(), "mit Zuruecklegen: 8 Zuege aus 3 Decks wiederholen sich: " + first);
    }

    @Test
    void drawLehntUngueltigeEingabenAb() {
        assertThrows(IllegalArgumentException.class, () -> SparringOpponents.draw(List.of(), 3, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> SparringOpponents.draw(List.of("A"), 0, new Random(1)));
    }
}
