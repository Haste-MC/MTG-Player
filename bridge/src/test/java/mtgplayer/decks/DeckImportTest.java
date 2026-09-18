package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.DeckSection;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DeckImportTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static final String ARCHIDEKT = String.join("\n",
            "Commander",
            "1x Felothar the Steadfast (tdc) 1 [Commander{top}]",
            "",
            "Main",
            "1x Sol Ring (c21) 263 [Ramp]",
            "3x Forest (tdc) 300 [Land]",
            "1x Swords to Plowshares [Removal]",
            "1x Gibtsnicht Karte [Test]",
            "");

    @Test
    void archidektExportWirdErkannt() {
        DeckImport.Result r = DeckImport.parse(ARCHIDEKT);
        assertEquals(1, r.deck().getCommanders().size(), "Commander erkannt");
        assertEquals("Felothar the Steadfast", r.deck().getCommanders().get(0).getName());
        assertEquals(5, r.deck().get(DeckSection.Main).countAll(), "1 Sol Ring + 3 Forest + 1 Swords");
        assertEquals(1, r.problems().size(), "eine unbekannte Karte");
        assertTrue(r.problems().get(0).contains("Gibtsnicht Karte"));
    }

    @Test
    void arenaFormatOhneSectionsNimmtErstenLegalenCommander() {
        String text = "1 Sol Ring\n1 Felothar the Steadfast\n2 Forest\n";
        DeckImport.Result r = DeckImport.parse(text);
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        assertEquals(1, r.deck().getCommanders().size());
        assertEquals("Felothar the Steadfast", r.deck().getCommanders().get(0).getName());
        assertEquals(3, r.deck().get(DeckSection.Main).countAll(), "Commander nicht mehr im Main");
    }

    @Test
    void ohneCommanderIstEinProblem() {
        DeckImport.Result r = DeckImport.parse("1 Sol Ring\n2 Forest\n");
        assertFalse(r.problems().isEmpty());
        assertTrue(r.problems().get(0).toLowerCase().contains("commander"));
    }

    @Test
    void leererTextIstEinProblem() {
        DeckImport.Result r = DeckImport.parse("   \n\n");
        assertFalse(r.problems().isEmpty());
    }

    @Test
    void unbekannteSektionWirdGemeldetUndKartenDanachNichtStillUebernommen() {
        DeckImport.Result r = DeckImport.parse("1 Sol Ring\nMaybeboard\n1 Felothar the Steadfast\n");
        assertFalse(r.problems().isEmpty(), "Maybeboard wird nicht erkannt und muss als Problem auftauchen");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("Maybeboard")), r.problems().toString());
        assertTrue(r.deck().getCommanders().isEmpty(), "Felothar darf nicht automatisch Commander werden");
        assertEquals(0, r.deck().has(DeckSection.Main) ? r.deck().get(DeckSection.Main).countAll(x -> x.getName().equals("Felothar the Steadfast")) : 0,
                "Felothar darf nach der unerkannten Zeile nicht still im Main landen");
    }

    @Test
    void nichtErkannteZeileIstGenauEinProblem() {
        DeckImport.Result r = DeckImport.parse("1 Sol Ring\n1 Felothar the Steadfast\nblabla kein kartenname\n");
        assertEquals(1, r.problems().size(), r.problems().toString());
        assertTrue(r.problems().get(0).contains("blabla"));
    }

    @Test
    void nichtKopfzeilenartigeUnbekannteZeileSchaltetSektionNichtAb() {
        DeckImport.Result r = DeckImport.parse("1 Sol Ring\nblabla kein kartenname\n1 Felothar the Steadfast\n");
        assertEquals(1, r.problems().size(), r.problems().toString());
        assertTrue(r.problems().get(0).contains("blabla"));
        assertEquals(1, r.deck().getCommanders().size(), "Felothar wird trotz der Zwischenzeile noch Commander");
        assertEquals("Felothar the Steadfast", r.deck().getCommanders().get(0).getName());
    }

    @Test
    void namensvorschlag() {
        DeckImport.Result r = DeckImport.parse(ARCHIDEKT);
        assertEquals("Felothar the Steadfast", DeckImport.suggestName(ARCHIDEKT, r.deck()));
        assertEquals("Mein Deck", DeckImport.suggestName("Name: Mein Deck\n" + ARCHIDEKT, r.deck()));
    }
}
