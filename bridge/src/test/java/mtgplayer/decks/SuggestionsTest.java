package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.stats.CardStats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class SuggestionsTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static PaperCard card(String name) {
        PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        assertNotNull(pc, "Karte nicht in der Datenbank: " + name);
        return pc;
    }

    /** Gruenes Commander-Deck mit Titania: ein Schutzzauber ist bereits drin, damit "schon im Deck" greift. */
    private static Deck deck(String... mainCards) {
        Deck d = new Deck("Test");
        d.getOrCreate(DeckSection.Commander).add(card("Titania, Protector of Argoth"));
        for (String n : mainCards) {
            d.getMain().add(card(n));
        }
        d.getMain().add(card("Forest"), 30);
        return d;
    }

    private static Edhrec.Page page(Edhrec.Card... cards) {
        return new Edhrec.Page("titania-protector-of-argoth", List.of(cards), Instant.now());
    }

    private static Edhrec.Card ec(String name, double share) { return new Edhrec.Card(name, share, false); }
    private static Edhrec.Card gc(String name, double share) { return new Edhrec.Card(name, share, true); }

    /** Kartenauswertung fuer genau eine Karte: {@code handGames} Partien auf der Hand, nie gewirkt, der
     *  Rest der Partien mit Kartendaten nie gezogen - so ergibt {@code handGames + neverDrawnGames} genau
     *  die Partien mit Kartendaten, wie es {@link mtgplayer.stats.CardStats#buildCard} auch tut. */
    private static Map<String, CardStats.Card> neverCastOwn(String name, int handGames, int withCardData) {
        int neverDrawnGames = withCardData - handGames;
        CardStats.Card card = new CardStats.Card(name, null, null, null, handGames, 0, null, handGames,
                neverDrawnGames, 0, 0);
        return Map.of(name, card);
    }

    @Test
    void schlaegtKartenDerGefragtenRolleVor() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("wipeProtection"), 4,
                page(ec("Heroic Intervention", 0.68), ec("Llanowar Elves", 0.4)));
        assertEquals(List.of("Heroic Intervention"), r.items().stream().map(Suggestions.Item::name).toList());
        assertEquals("wipeProtection", r.items().get(0).role());
        assertEquals(0.68, r.items().get(0).share(), 1e-9);
        assertEquals("edhrec", r.source());
    }

    @Test
    void laesstWegWasSchonImDeckIst() {
        Suggestions.Result r = Suggestions.of(deck("Heroic Intervention"), List.of("wipeProtection"), 4,
                page(ec("Heroic Intervention", 0.68)));
        assertTrue(r.items().isEmpty(), r.items().toString());
    }

    @Test
    void laesstWegWasNichtZurFarbidentitaetPasst() {
        // Counterspell ist blau, Titania gruen - darf nicht vorgeschlagen werden.
        Suggestions.Result r = Suggestions.of(deck(), List.of("counters"), 4, page(ec("Counterspell", 0.5)));
        assertTrue(r.items().isEmpty(), r.items().toString());
    }

    @Test
    void laesstLaenderWeg() {
        // Ancient Tomb allein beweist nichts: die "ramp"-Regel in DeckAnalysis schliesst Laender schon
        // selbst aus (landType(c) == null), der Test bliebe also auch ohne Land-Filter in Suggestions gruen.
        // Load-bearing ist der Filter bei einer Rolle ohne eingebaute Land-Ausnahme: "tutors" trifft auf
        // "search your library for" und damit auch auf ein Fetchland wie Evolving Wilds (laut
        // DeckAnalysis.categoriesOf ausschliesslich "tutors", keine "ramp") - genau der Fall, den der
        // Land-Filter in Suggestions abfangen muss.
        Suggestions.Result r = Suggestions.of(deck(), List.of("tutors"), 4, page(ec("Evolving Wilds", 0.6)));
        assertTrue(r.items().stream().noneMatch(i -> i.name().equals("Evolving Wilds")), r.items().toString());
    }

    @Test
    void sortiertNachAnteilDannKosten() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4,
                page(ec("Cultivate", 0.5), ec("Llanowar Elves", 0.5), ec("Rampant Growth", 0.7)));
        // Rampant Growth (0.7) vor den beiden mit 0.5; dort zuerst die billigere Karte.
        assertEquals(List.of("Rampant Growth", "Llanowar Elves", "Cultivate"),
                r.items().stream().map(Suggestions.Item::name).toList());
    }

    @Test
    void hoechstensFuenfJeRolle() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"),  4,
                page(ec("Llanowar Elves", 0.9), ec("Elvish Mystic", 0.8), ec("Fyndhorn Elves", 0.7),
                     ec("Rampant Growth", 0.6), ec("Cultivate", 0.5), ec("Kodama's Reach", 0.4)));
        assertEquals(5, r.items().size());
    }

    @Test
    void doppelteRolleBegrenztAufFuenf() {
        // roles = ["ramp", "ramp"] darf die Kandidatenliste nicht zweimal durchlaufen - sonst kaemen bis
        // zu zehn statt hoechstens PER_ROLE Vorschlaege fuer dieselbe Rolle heraus.
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp", "ramp"), 4,
                page(ec("Llanowar Elves", 0.9), ec("Elvish Mystic", 0.8), ec("Fyndhorn Elves", 0.7),
                     ec("Rampant Growth", 0.6), ec("Cultivate", 0.5), ec("Kodama's Reach", 0.4)));
        assertEquals(5, r.items().size());
    }

    @Test
    void bracketZweiLaesstGameChangerWeg() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 2,
                page(gc("Crop Rotation", 0.8), ec("Llanowar Elves", 0.4)));
        assertEquals(List.of("Llanowar Elves"), r.items().stream().map(Suggestions.Item::name).toList());
    }

    @Test
    void bracketDreiNimmtGameChangerMitHinweis() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 3, page(gc("Crop Rotation", 0.8)));
        assertEquals(1, r.items().size());
        assertTrue(r.items().get(0).gameChanger());
        assertNotNull(r.note());
        assertTrue(r.note().contains("Bracket 3"), r.note());
    }

    @Test
    void ohneRollenNichts() {
        Suggestions.Result r = Suggestions.of(deck(), List.of(), 4, page(ec("Llanowar Elves", 0.4)));
        assertTrue(r.items().isEmpty());
        assertNotNull(r.note());
    }

    @Test
    void traegtAnzeigedatenMit() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4, page(ec("Llanowar Elves", 0.4)));
        Suggestions.Item i = r.items().get(0);
        assertEquals(1, i.cmc());
        assertEquals("{G}", i.manaCost());
        assertFalse(i.imageKey().isBlank());
        assertFalse(i.text().isBlank());
    }

    @Test
    void traegtDasAbrufdatumDerSeiteMit() {
        // Kevin soll sehen koennen, wie alt der EDHREC-Stand ist, den die Vorschlaege benutzen - dafuer
        // muss Result.fetched() genau der Instant sein, den die uebergebene Page traegt.
        Instant fetched = Instant.parse("2026-09-01T12:00:00Z");
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4,
                new Edhrec.Page("titania-protector-of-argoth", List.of(ec("Llanowar Elves", 0.4)), fetched));
        assertEquals(fetched, r.fetched());
    }

    @Test
    void ohneEdhrecFehltDasAbrufdatum() {
        // Der Datenbank-Rueckfall hat keinen EDHREC-Stand - "fehlt" statt eines erfundenen Datums.
        Suggestions.Result r = Suggestions.of(deck(), List.of("wipeProtection"), 4, null);
        assertEquals("db", r.source());
        assertNull(r.fetched());
    }

    @Test
    void schneidetUnklassifizierteKarteWennKeineRolleTrifft() {
        // Overrun ist ein reiner Kampftrick/Pump-Zauber: kein "add" (ramp), kein "draw", keine Zerstoerung,
        // kein Schutz- oder Friedhof-Text, keine Landsuche - DeckAnalysis.categoriesOf(Overrun) ist leer.
        // Der Test deckt also Regel 3 ab (Rueckfall auf unklassifizierte Karten), nicht Regel 1 (gleiche
        // Rolle) - dafuer gibt es eigene Tests weiter unten (fastNiemand/niedrigsterAnteil/undVerkettung).
        assertTrue(DeckAnalysis.categoriesOf(card("Overrun").getRules()).isEmpty(),
                "Testannahme verletzt: Overrun trifft jetzt doch eine Rolle");
        Suggestions.Result r = Suggestions.of(deck("Overrun"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4)));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Overrun", cut.name());
        assertEquals("trifft keine der neun Rollen", cut.reason());
    }

    @Test
    void begruendetNiedrigenAnteilAlsFastNiemand() {
        // Zwei Kandidaten derselben Rolle im Deck, beide auf der Seite gefuehrt: Elvish Mystic hat den
        // niedrigeren (und niedrigen, <10%) Anteil und ist nicht die teuerste der beiden - reine Anteilsfrage.
        Suggestions.Result r = Suggestions.of(deck("Elvish Mystic", "Rampant Growth"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4), ec("Elvish Mystic", 0.04), ec("Rampant Growth", 0.5)));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Elvish Mystic", cut.name());
        assertEquals("spielt in vergleichbaren Decks fast niemand (4 %)", cut.reason());
    }

    @Test
    void begruendetHohenAnteilAlsNiedrigstenDerRolle() {
        // Beide Kandidaten haben einen hohen Anteil (>=10%); Elvish Mystic ist trotzdem der niedrigere der
        // beiden und nicht die teuerste - "fast niemand" waere hier falsch, es ist nur der schwaechste
        // Kandidat eines starken Feldes.
        Suggestions.Result r = Suggestions.of(deck("Elvish Mystic", "Rampant Growth"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4), ec("Elvish Mystic", 0.85), ec("Rampant Growth", 0.9)));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Elvish Mystic", cut.name());
        assertEquals("hat mit 85 % den niedrigsten Anteil der Karten dieser Rolle in deinem Deck", cut.reason());
    }

    @Test
    void begruendetFehlendenEdhrecEintragOhneAnteil() {
        // Elvish Mystic steht gar nicht auf der Seite, Rampant Growth schon - "nicht gefuehrt" gewinnt
        // unabhaengig vom Anteil, und Elvish Mystic ist nicht die teuerste der beiden.
        Suggestions.Result r = Suggestions.of(deck("Elvish Mystic", "Rampant Growth"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4), ec("Rampant Growth", 0.5)));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Elvish Mystic", cut.name());
        assertEquals("führt EDHREC für diesen Commander gar nicht", cut.reason());
    }

    @Test
    void verkettetZweiZutreffendeGruendeMitUnd() {
        // Cultivate steht nicht auf der Seite UND ist mit {2}{G} die teuerste der beiden Kandidaten -
        // beide Gruende treffen zu und muessen verkettet werden.
        Suggestions.Result r = Suggestions.of(deck("Cultivate", "Rampant Growth"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4), ec("Rampant Growth", 0.5)));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Cultivate", cut.name());
        assertEquals("führt EDHREC für diesen Commander gar nicht und ist mit 2 {G} die teuerste Karte dieser Rolle",
                cut.reason());
    }

    @Test
    void schneidetJedeKarteHoechstensEinmal() {
        Suggestions.Result r = Suggestions.of(deck("Cultivate"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.9), ec("Rampant Growth", 0.8)));
        List<String> cuts = r.items().stream().map(i -> i.cut() == null ? null : i.cut().name()).toList();
        assertEquals(1, cuts.stream().filter(java.util.Objects::nonNull).distinct().count(), cuts.toString());
        assertTrue(cuts.contains(null), "der zweite Vorschlag haette keinen Schnitt mehr: " + cuts);
    }

    @Test
    void schneidetNieLandOderCommander() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4, page(ec("Llanowar Elves", 0.9)));
        Suggestions.Cut cut = r.items().get(0).cut();
        if (cut != null) {
            assertFalse(cut.name().equals("Forest") || cut.name().equals("Titania, Protector of Argoth"),
                    "Land oder Commander als Schnitt: " + cut);
        }
    }

    @Test
    void ohneEdhrecKommenVorschlaegeAusDerDatenbank() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("wipeProtection"), 4, null);
        assertEquals("db", r.source());
        assertFalse(r.items().isEmpty(), "auch ohne EDHREC muss die Datenbank etwas liefern");
        assertTrue(r.items().stream().allMatch(i -> i.share() == null), "ohne EDHREC gibt es keinen Anteil");
        assertNotNull(r.note());
        // Farbidentitaet gilt auch hier.
        assertTrue(r.items().stream().noneMatch(i -> i.name().equals("Teferi's Protection")));
    }

    @Test
    void datenbankRueckfallSortiertNachKosten() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4, null);
        List<Integer> cmcs = r.items().stream().map(Suggestions.Item::cmc).toList();
        assertEquals(cmcs.stream().sorted().toList(), cmcs, cmcs.toString());
    }

    @Test
    void datenbankRueckfallWendetBracketRegelAufGameChangerAn() {
        // Befund 4: "dort liegt keine Game-Changer-Kennzeichnung vor" war falsch - Forge liefert die Liste
        // selbst mit (gamechangers.txt). Ohne Bracket-Einschraenkung taucht Chrome Mox (Game Changer, gruen,
        // manarock -> ramp) im Rueckfall auf - Testannahme, damit der zweite Teil ueberhaupt etwas beweist.
        Suggestions.Result unrestricted = Suggestions.of(deck(), List.of("ramp"), 4, null);
        assertTrue(unrestricted.items().stream().anyMatch(i -> i.name().equals("Chrome Mox") && i.gameChanger()),
                "Testannahme verletzt: Chrome Mox muesste im freien Rueckfall auftauchen: " + unrestricted.items());
        // Bracket 2 verbietet Game Changer - dieselbe Regel wie im EDHREC-Zweig, jetzt auch ohne EDHREC-Seite.
        Suggestions.Result restricted = Suggestions.of(deck(), List.of("ramp"), 2, null);
        assertFalse(restricted.items().stream().anyMatch(i -> i.name().equals("Chrome Mox")),
                "Chrome Mox haette als Game Changer im Bracket-2-Rueckfall fehlen muessen: " + restricted.items());
    }

    @Test
    void loestDoppelseitigenKandidatennamenAufDieVorderseiteAuf() {
        // Befund 8: EDHREC fuehrt doppelseitige Karten unter ihrem vollen "Vorderseite // Rueckseite"-Namen
        // (auf der echten Titania-Seite steht mindestens ein solcher Name) - Forges Kartensuche kennt nur
        // die Vorderseite. Ohne den Schnitt an "//" liefe diese Karte nie in den Vorschlaegen auf.
        Suggestions.Result r = Suggestions.of(deck(), List.of("recursion"), 4,
                page(ec("Bala Ged Recovery // Bala Ged Sanctuary", 0.3)));
        assertEquals(List.of("Bala Ged Recovery"), r.items().stream().map(Suggestions.Item::name).toList());
    }

    @Test
    void einzigerKandidatBekommtKeineSuperlativKlausel() {
        // Befund 2: mit nur einer Karte der Rolle im Deck ist "die teuerste Karte dieser Rolle" nichts, was
        // sich pruefen liesse (eine einelementige Liste ist immer ihr eigenes Maximum) - die Klausel darf
        // dann nicht angehaengt werden, auch wenn der (dann einzige) Kandidat sie frueher immer bekam.
        Suggestions.Result r = Suggestions.of(deck("Rampant Growth"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4), ec("Rampant Growth", 0.04)));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Rampant Growth", cut.name());
        assertEquals("spielt in vergleichbaren Decks fast niemand (4 %)", cut.reason(),
                "kein einziger Kandidat darf zusaetzlich als \"teuerste Karte\" durchgehen");
    }

    @Test
    void zweiterSchnittDerselbenRolleBehauptetKeinenFalschenNiedrigstenAnteil() {
        // Befund 1, das im Review gemeldete Fixture-Beispiel nachgebaut: zwei Vorschlaege derselben Rolle,
        // das Deck hat zwei ramp-Karten (Elvish Mystic 4 %, Kodama's Reach 31 %). Der erste Schnitt nimmt
        // die mit dem niedrigsten Anteil (Elvish Mystic). Der zweite Schnitt kann sie nicht mehr nehmen
        // (Regel 5) und landet bei Kodama's Reach - die darf dann aber NICHT als "niedrigster Anteil"
        // durchgehen, denn Elvish Mystic (4 %) steht ja weiterhin im Deck.
        Suggestions.Result r = Suggestions.of(deck("Elvish Mystic", "Kodama's Reach"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.9), ec("Cultivate", 0.8),
                     ec("Elvish Mystic", 0.04), ec("Kodama's Reach", 0.31)));
        List<Suggestions.Item> ramp = r.items().stream().filter(i -> i.role().equals("ramp")).toList();
        assertEquals(2, ramp.size(), ramp.toString());
        Suggestions.Cut firstCut = ramp.get(0).cut();
        assertNotNull(firstCut, "ohne ersten Schnitt: " + ramp);
        assertEquals("Elvish Mystic", firstCut.name());
        Suggestions.Cut secondCut = ramp.get(1).cut();
        assertNotNull(secondCut, "ohne zweiten Schnitt: " + ramp);
        assertEquals("Kodama's Reach", secondCut.name());
        assertFalse(secondCut.reason().contains("niedrigsten Anteil"),
                "Kodama's Reach ist NICHT der niedrigste Anteil - Elvish Mystic (4 %) steht noch im Deck: "
                        + secondCut.reason());
        assertEquals("ist mit 2 {G} die teuerste Karte dieser Rolle", secondCut.reason());
    }

    // -- Task 5: Schnittgrund aus eigenen Partien -------------------------------------------------------

    @Test
    void schnittAusEigenenPartienSticheDieAltenRegeln() {
        // Ohne eigene Partien waere Cultivate der Schnitt (Befund aus verkettetZweiZutreffendeGruendeMitUnd:
        // nicht auf der Seite gefuehrt UND mit {2}{G} teurer als Rampant Growth {1}{G}) - genau die "bisherigen
        // Regeln (kein EDHREC-Eintrag, teurer)", die laut Task-5-Brief von der neuen Stufe 0 gestochen werden.
        // Rampant Growth war laut eigener Auswertung in 9 Partien 6x auf der Hand und nie gewirkt.
        Suggestions.Result r = Suggestions.of(deck("Cultivate", "Rampant Growth"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4), ec("Rampant Growth", 0.5)),
                neverCastOwn("Rampant Growth", 6, 9));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Rampant Growth", cut.name());
        assertEquals("in 9 Partien 6× auf der Hand, nie gewirkt", cut.reason());
    }

    @Test
    void wenigerAlsDreiHandPartienLaesstDieAltenRegelnUnveraendert() {
        // Dieselbe Fixture wie oben, aber nur 2 Hand-Partien - unter der Schwelle von 3 beweist "nie
        // gewirkt" noch nichts, die alte Rangfolge (Cultivate) muss unveraendert gewinnen.
        Suggestions.Result r = Suggestions.of(deck("Cultivate", "Rampant Growth"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4), ec("Rampant Growth", 0.5)),
                neverCastOwn("Rampant Growth", 2, 5));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Cultivate", cut.name());
        assertEquals("führt EDHREC für diesen Commander gar nicht und ist mit 2 {G} die teuerste Karte dieser Rolle",
                cut.reason());
    }

    @Test
    void leereKartenauswertungAendertNichtsGegenprobe() {
        // Gegenprobe gegen Regression: dieselbe Fixture, aber ohne eigene Kartenauswertung (die Bridge
        // uebergibt eine leere Map, wenn CardStats#enough nicht gilt) - das Verhalten muss exakt dem der
        // alten 4-Parameter-Ueberladung entsprechen.
        Edhrec.Page samePage = page(ec("Llanowar Elves", 0.4), ec("Rampant Growth", 0.5));
        Suggestions.Result withEmptyOwn = Suggestions.of(deck("Cultivate", "Rampant Growth"), List.of("ramp"), 4,
                samePage, Map.of());
        Suggestions.Result withoutOwnAtAll = Suggestions.of(deck("Cultivate", "Rampant Growth"), List.of("ramp"), 4,
                samePage);
        assertEquals(withoutOwnAtAll, withEmptyOwn);
        Suggestions.Cut cut = withEmptyOwn.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + withEmptyOwn.items());
        assertEquals("Cultivate", cut.name());
        assertEquals("führt EDHREC für diesen Commander gar nicht und ist mit 2 {G} die teuerste Karte dieser Rolle",
                cut.reason());
    }
}
