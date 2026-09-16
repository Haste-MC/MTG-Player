package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.deck.DeckSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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

    @Test
    void loadUnbekanntWirft() {
        assertThrows(IllegalArgumentException.class, () -> Precons.load("Gibt es nicht"));
    }
}
