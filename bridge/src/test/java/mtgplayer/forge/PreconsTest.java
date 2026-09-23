package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

class PreconsTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void namesListetAlleCommanderPrecons() {
        var names = Precons.names();
        assertTrue(names.size() > 100, "erwartet > 100 Precons, war " + names.size());
        assertTrue(names.contains("Abzan Armor [TDC] [2025]"));
        assertEquals(names, names.stream().sorted().toList(), "muss sortiert sein");
    }

    @Test
    void loadLiefertVollstaendigesDeck() {
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        assertEquals(1, d.getCommanders().size());
        assertEquals("Felothar the Steadfast", d.getCommanders().get(0).getName());
        assertEquals(99, d.get(DeckSection.Main).countAll());
    }

    /**
     * Forges {@code IStorage} gibt bei jedem Aufruf dieselbe Deck-Instanz heraus - wer sie taggt oder
     * umbenennt, veraendert das Precon fuer die ganze JVM (und damit fuer jeden spaeteren Test und jede
     * spaetere Partie). {@code load} kopiert deshalb; diese drei Faelle halten das fest.
     */
    @Test
    void loadLiefertJedesMalEineEigeneInstanz() {
        String n = "Abzan Armor [TDC] [2025]";
        assertNotSame(Precons.load(n), Precons.load(n));
    }

    @Test
    void einTagAufDerKopieIstBeimNaechstenLoadWiederWeg() {
        String n = "Adaptive Enchantment [C18] [2018]";
        Precons.load(n).getTags().add("mtgplayer-test-tag");
        assertFalse(Precons.load(n).getTags().contains("mtgplayer-test-tag"),
                "das Tag haengt am Precon der ganzen JVM statt an der Kopie");
    }

    /** Eine Kopie nuetzt nichts, wenn dabei der Inhalt verloren geht - Kommandeur und Hauptdeck muessen mit. */
    @Test
    void dieKopieTraegtKommandeurUndHauptdeckUndUeberlebtEineAenderungDaran() {
        String n = "Abzan Armor [TDC] [2025]";
        Deck erste = Precons.load(n);
        assertEquals(List.of("Felothar the Steadfast"), erste.getCommanders().stream().map(PaperCard::getName).toList());
        assertEquals(99, erste.get(DeckSection.Main).countAll());

        erste.getTags().add("mtgplayer-test-tag");
        erste.get(DeckSection.Main).remove(erste.get(DeckSection.Main).toFlatList().get(0));

        Deck zweite = Precons.load(n);
        assertEquals(List.of("Felothar the Steadfast"), zweite.getCommanders().stream().map(PaperCard::getName).toList());
        assertEquals(99, zweite.get(DeckSection.Main).countAll(), "die Aenderung hat das Precon selbst getroffen");
    }

    @Test
    void loadUnbekanntWirft() {
        assertThrows(IllegalArgumentException.class, () -> Precons.load("Gibt es nicht"));
    }
}
