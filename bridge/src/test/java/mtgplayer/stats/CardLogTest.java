package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Zusammenfassung der Kartenzeilen (Spec 2026-09-25-kartenaufzeichnung §2). Vier Kopien derselben Karte
 *  werden eine Zeile: Zaehler addiert, castTurn der frueheste. */
class CardLogTest {

    private static CardLog.Card card(String name, int hand, int cast, Integer castTurn, String end) {
        return new CardLog.Card(name, 1, hand, cast, castTurn, 0, 0, end);
    }

    @Test
    void fasstKopienZusammen() {
        List<CardLog.Card> merged = CardLog.merge(List.of(
                card("Cultivate", 1, 1, 5, "graveyard"),
                card("Cultivate", 1, 0, null, "library")));
        assertEquals(1, merged.size());
        CardLog.Card c = merged.get(0);
        assertEquals(2, c.copies());
        assertEquals(2, c.hand());
        assertEquals(1, c.cast());
        assertEquals(5, c.castTurn(), "frueheste Wirkung zaehlt");
    }

    @Test
    void behaeltEinzelneKarteUnveraendert() {
        List<CardLog.Card> merged = CardLog.merge(List.of(card("Nesting Dragon", 1, 0, null, "hand")));
        assertEquals(1, merged.size());
        assertEquals("hand", merged.get(0).end());
        assertNull(merged.get(0).castTurn());
    }

    @Test
    void sortiertNachNamen() {
        List<CardLog.Card> merged = CardLog.merge(List.of(
                card("Zuran Orb", 1, 0, null, "library"), card("Cultivate", 1, 0, null, "library")));
        assertEquals(List.of("Cultivate", "Zuran Orb"), merged.stream().map(CardLog.Card::name).toList());
    }

    @Test
    void ohneZeilenLeereListe() {
        assertTrue(CardLog.merge(List.of()).isEmpty());
    }

    // -- Befund 5: eine fehlende Liste (formfremdes, aber gueltiges JSON) darf nie zu einer
    // NullPointerException in der Auswertung fuehren - der kanonische Konstruktor normalisiert sie hier
    // direkt am Record, nicht erst irgendwo tiefer in CardStats. --------------------------------------

    @Test
    void fehlendeSeatsListeWirdImKanonischenKonstruktorZuLeererListe() {
        // So kommt ein {"v":1,"id":"m3"} (kein "seats"-Feld) bei Jackson an: der kanonische Konstruktor
        // bekommt null.
        CardLog log = new CardLog(1, "m3", null);
        assertEquals(List.of(), log.seats());
    }

    @Test
    void fehlendeCardsListeInSeatCardsWirdImKanonischenKonstruktorZuLeererListe() {
        CardLog.SeatCards seat = new CardLog.SeatCards(0, "Mein Deck", null);
        assertEquals(List.of(), seat.cards());
    }
}
