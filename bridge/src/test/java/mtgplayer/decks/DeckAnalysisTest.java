package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.StaticData;
import forge.ai.AiDeckStatistics;
import forge.card.CardRules;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Deckanalyse rein aus der Kartendatenbank. Die Kategorie-Tests gehen bewusst ueber einzelne, bekannte
 * Karten (und nicht ueber ganze Decks): so ist bei einem Fehlschlag sofort klar, welche Regel danebenliegt.
 */
class DeckAnalysisTest {

    private static final String PRECON = "Abzan Armor [TDC] [2025]";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static PaperCard card(String name) {
        PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
        assertNotNull(pc, "Karte nicht in der Datenbank: " + name);
        return pc;
    }

    private static CardRules rules(String name) {
        return card(name).getRules();
    }

    private static List<String> cats(String name) {
        return DeckAnalysis.categoriesOf(rules(name));
    }

    // ---------------------------------------------------------------- ganzes Deck

    @Test
    void preconLiefertPlausibleWerte() {
        DeckAnalysis a = DeckAnalysis.of(Precons.load(PRECON));
        assertEquals(100, a.cards(), "99 Hauptdeck + 1 Kommandeur");
        assertTrue(a.lands() > 30, "Laender: " + a.lands());
        assertTrue(a.basics() > 0 && a.basics() <= a.lands(), "Standardlaender: " + a.basics());
        assertTrue(a.avgCmc() > 0, "avgCmc: " + a.avgCmc());
        int curveSum = a.curve().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(a.cards(), curveSum + a.lands(), "Kurve (Nicht-Laender) + Laender == Karten");
        assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7+"), List.copyOf(a.curve().keySet()));
        assertEquals(List.of("W", "U", "B", "R", "G", "any"), List.copyOf(a.sources().keySet()));
        assertEquals(DeckAnalysis.rules().stream().map(DeckAnalysis.Rule::name).toList(),
                List.copyOf(a.categories().keySet()), "jede Regel hat einen Eintrag");
        assertTrue(a.unclassified() > 0, "ein 100-Karten-Deck trifft nie jede Karte: " + a.unclassified());
        assertTrue(a.unclassified() <= a.cards() - a.lands(),
                "unclassified zaehlt nur Nicht-Laender: " + a.unclassified() + " von " + (a.cards() - a.lands()));
        assertEquals(List.of("W", "B", "G"), a.identity(), "Abzan = WBG, in WUBRG-Reihenfolge");
    }

    /**
     * Laender und Ø-CMC sind bei Forge schon definiert (AiDeckStatistics: CMC ueber Nicht-Laender,
     * Haupt- und Kommandeursektion) - wir uebernehmen diese Definition und halten sie hier fest,
     * damit unsere Zahlen nicht still von Forges auseinanderlaufen.
     */
    @Test
    void laenderUndDurchschnittsCmcStimmenMitAiDeckStatisticsUeberein() {
        Deck deck = Precons.load(PRECON);
        DeckAnalysis a = DeckAnalysis.of(deck);
        AiDeckStatistics forge = AiDeckStatistics.fromDeck(deck);
        assertEquals(forge.numLands, a.lands());
        assertEquals(forge.averageCMC, a.avgCmc(), 0.05, "gerundet auf eine Nachkommastelle");
    }

    @Test
    void farbquellenEinesZweifarbigenDecks() {
        Deck deck = new Deck("Dimir-Test");
        CardPool main = deck.getOrCreate(DeckSection.Main);
        main.add(card("Island"), 5);
        main.add(card("Swamp"), 4);
        main.add(card("Command Tower"), 2);
        main.add(card("Dimir Aqueduct"), 1);   // {T}: Add {U}{B}
        main.add(card("Watery Grave"), 1);     // Land - Island Swamp
        main.add(card("Sol Ring"), 1);         // kein Land: keine Farbquelle
        deck.getOrCreate(DeckSection.Commander).add(card("Lazav, Dimir Mastermind"), 1);

        DeckAnalysis a = DeckAnalysis.of(deck);
        assertEquals(15, a.cards());
        assertEquals(13, a.lands());
        assertEquals(9, a.basics());
        assertEquals(0, a.sources().get("W"));
        assertEquals(7, a.sources().get("U"), "5 Island + Aqueduct + Watery Grave");
        assertEquals(6, a.sources().get("B"), "4 Swamp + Aqueduct + Watery Grave");
        assertEquals(0, a.sources().get("R"));
        assertEquals(0, a.sources().get("G"));
        assertEquals(2, a.sources().get("any"), "Command Tower");
        assertEquals(List.of("U", "B"), a.identity(), "aus dem Kommandeur");
        // Kurve: nur Nicht-Laender (Sol Ring, CMC 1) und der Kommandeur (CMC 4)
        assertEquals(1, a.curve().get("1"));
        assertEquals(1, a.curve().get("4"));
    }

    /**
     * Laender sind nicht "nicht eingeordnet" - sie haben mit lands/basics/sources ihren eigenen Ausweis.
     * Ein reines Landdeck hat deshalb unclassified == 0, obwohl keine Karte eine Kategorie trifft.
     */
    @Test
    void laenderZaehlenNichtAlsUnclassified() {
        Deck deck = new Deck("Nur Laender");
        deck.getOrCreate(DeckSection.Main).add(card("Island"), 3);
        DeckAnalysis a = DeckAnalysis.of(deck);
        assertEquals(3, a.cards());
        assertEquals(3, a.lands());
        assertEquals(0, a.unclassified());
    }

    /**
     * Modal-DFC mit Land-Rueckseite: Kevin spielt Agadeem's Awakening als Land, also zaehlt es hier als
     * Land (und nicht in der Kurve) - anders als Forges AiDeckStatistics, das nur die Vorderseite kennt.
     * Die Rueckseite liefert ausserdem ihre Farbquelle; die Vorderseite bleibt fuer die Kategorien gueltig.
     */
    @Test
    void modalesLandZaehltAlsLandNichtAlsZauber() {
        Deck deck = new Deck("MDFC-Test");
        deck.getOrCreate(DeckSection.Main).add(card("Agadeem's Awakening"), 1);
        deck.getOrCreate(DeckSection.Main).add(card("Island"), 1);
        DeckAnalysis a = DeckAnalysis.of(deck);
        assertEquals(2, a.cards());
        assertEquals(2, a.lands(), "die Rueckseite ist ein Land");
        assertEquals(1, a.basics(), "nur die Insel ist ein Standardland");
        assertEquals(0, a.curve().values().stream().mapToInt(Integer::intValue).sum(), "nicht in der Kurve");
        assertEquals(0.0, a.avgCmc());
        assertEquals(1, a.sources().get("B"), "{T}: Add {B} auf der Rueckseite");
        assertEquals(1, a.sources().get("U"));
        assertTrue(cats("Agadeem's Awakening").contains("recursion"), "Text der Vorderseite zaehlt weiter");
        assertFalse(cats("Agadeem's Awakening").contains("ramp"), "ein Land ist kein Ramp");
        assertTrue(AiDeckStatistics.fromDeck(deck).numLands < a.lands(),
                "bewusster Unterschied zu Forge: dort ist die Vorderseite ein Zauber");
    }

    @Test
    void deckOhneKommandeurHatLeereIdentitaet() {
        Deck deck = new Deck("Nur Hauptdeck");
        deck.getOrCreate(DeckSection.Main).add(card("Grizzly Bears"), 1);
        assertEquals(List.of(), DeckAnalysis.of(deck).identity());
    }

    // ---------------------------------------------------------------- je Kategorie eine bekannte Karte

    @Test
    void cultivateIstRampUndTutor() {
        assertTrue(cats("Cultivate").containsAll(List.of("ramp", "tutors")), cats("Cultivate").toString());
    }

    @Test
    void solRingUndLlanowarElvesSindRampLaenderNicht() {
        assertTrue(cats("Sol Ring").contains("ramp"));
        assertTrue(cats("Llanowar Elves").contains("ramp"));
        assertTrue(cats("Arcane Signet").contains("ramp"), "\"Add one mana of any color\" ohne Manasymbol");
        assertFalse(cats("Island").contains("ramp"), "ein Land ist kein Ramp");
    }

    @Test
    void divinationIstDrawTempleBellNicht() {
        assertTrue(cats("Divination").contains("draw"));
        assertFalse(cats("Temple Bell").contains("draw"), "\"Each player draws a card\" ist kein eigener Zug");
    }

    /**
     * Erinnerungstext zaehlt nicht: Deceptive Landscape hat kein Ziehen im Regeltext, nur den
     * Klammertext von Cycling ("... Discard this card: Draw a card.") - sonst waere jede Karte mit
     * Cycling ein Kartenzieher.
     */
    @Test
    void cyclingAlleinIstKeinDraw() {
        assertFalse(cats("Deceptive Landscape").contains("draw"), cats("Deceptive Landscape").toString());
        assertTrue(cats("Deceptive Landscape").contains("tutors"), "die Landsuche bleibt");
    }

    @Test
    void murderIstRemoval() {
        assertTrue(cats("Murder").contains("removal"));
        assertTrue(cats("Beast Within").contains("removal"), "Destroy target permanent");
        assertFalse(cats("Divination").contains("removal"));
    }

    @Test
    void dayOfJudgmentIstWipe() {
        assertTrue(cats("Day of Judgment").contains("wipes"));
        assertTrue(cats("Slaughter the Strong").contains("wipes"), "\"sacrifices all other creatures\"");
        assertTrue(cats("Blasphemous Act").contains("wipes"), "\"deals 13 damage to each creature\"");
        assertFalse(cats("Fog").contains("wipes"), "\"prevent all combat damage\" raeumt nichts ab");
        assertFalse(cats("Murder").contains("wipes"), "eine einzelne Entfernung ist kein Wipe");
        assertFalse(cats("Bojuka Bog").contains("wipes"),
                "\"exile all cards from target player's graveyard\" raeumt keinen Tisch ab");
    }

    @Test
    void counterspellIstCounter() {
        assertTrue(cats("Counterspell").contains("counters"));
        assertTrue(cats("Essence Scatter").contains("counters"), "\"Counter target creature spell\"");
    }

    @Test
    void giantSpiderIstFlyerDefense() {
        assertTrue(cats("Giant Spider").contains("flyerDefense"), "Reach");
        assertTrue(cats("Serra Angel").contains("flyerDefense"), "Flying");
        assertFalse(cats("Grizzly Bears").contains("flyerDefense"));
    }

    @Test
    void heroicInterventionIstWipeProtection() {
        assertTrue(cats("Heroic Intervention").contains("wipeProtection"), "Hexproof und Indestructible");
        assertFalse(cats("Wrath of God").contains("wipeProtection"),
                "\"They can't be regenerated\" ist kein Schutz");
    }

    @Test
    void eternalWitnessIstRecursion() {
        assertTrue(cats("Eternal Witness").contains("recursion"));
        assertTrue(cats("Rise of the Dark Realms").contains("recursion"), "aus allen Friedhoefen");
    }

    @Test
    void grizzlyBearsTrifftKeineKategorie() {
        assertEquals(List.of(), cats("Grizzly Bears"));
    }

    @Test
    void jedeRegelHatEinenEindeutigenNamen() {
        List<String> names = DeckAnalysis.rules().stream().map(DeckAnalysis.Rule::name).toList();
        assertEquals(names.size(), names.stream().distinct().count(), names.toString());
        assertEquals(List.of("ramp", "draw", "removal", "wipes", "counters",
                "flyerDefense", "wipeProtection", "recursion", "tutors"), names);
    }
}
