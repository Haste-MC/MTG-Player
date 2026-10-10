package mtgplayer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchStatsTest {
    private static GameRecord g(int i, String w, int turns) {
        return g(i, w, turns, w == null);
    }

    private static GameRecord g(int i, String w, int turns, boolean turnCapped) {
        return new GameRecord(i, 1 + i, i % 2 == 0 ? "A" : "B", w, w == null ? "Draw" : "AllOpponentsLost", turns, 1000,
                turnCapped);
    }

    @Test void wilsonBekannteWerte() {
        double[] ci = BenchStats.wilson(30, 40, 1.96);      // p = 0.75 → ca. [0.597, 0.859]
        assertEquals(0.597, ci[0], 0.005); assertEquals(0.859, ci[1], 0.005);
        double[] none = BenchStats.wilson(0, 0, 1.96);
        assertEquals(0, none[0]); assertEquals(1, none[1]);
    }
    @Test void summaryZaehltSiegeUnentschiedenUndZuege() {
        var s = BenchStats.summarize(List.of(g(0, "A", 20), g(1, "B", 30), g(2, "A", 40), g(3, null, 200)));
        assertEquals(4, s.games()); assertEquals(2, s.winsA()); assertEquals(1, s.winsB()); assertEquals(1, s.draws());
        assertEquals(2.0 / 3, s.winRateA(), 1e-9);          // nur entschiedene Spiele
        assertEquals(72.5, s.avgTurns(), 1e-9); assertEquals(35, s.medianTurns(), 1e-9);
    }

    /** Forge kann ein Spiel selbst mit reason "Draw" beenden (gleichzeitiger Verlust, Stack > 999,
     *  GameDrawEffect) - nur das turnCapped-Flag, nicht der reason-String, darf drawsByTurnCap zaehlen. */
    @Test void drawsByTurnCapZaehltNurUeberDasFlag() {
        GameRecord vomZugdeckel = g(0, null, 50, true);
        GameRecord forgeEigenesDraw = g(1, null, 60, false);
        var s = BenchStats.summarize(List.of(vomZugdeckel, forgeEigenesDraw));
        assertEquals(2, s.draws());
        assertEquals(1, s.drawsByTurnCap());
    }

    @Test
    void abstuerzeZaehlenGetrenntUndNichtInDurchschnitten() {
        GameRecord crash = GameRecord.crash(2, 3, "?", new RuntimeException("Couldn't map <Nothing>/1"), 5000);
        assertTrue(crash.crashed());
        assertTrue(crash.reason().startsWith("Crash: RuntimeException: Couldn't map"));
        var s = BenchStats.summarize(List.of(g(0, "A", 20), g(1, null, 40), crash));
        assertEquals(3, s.games());
        assertEquals(1, s.crashes());
        assertEquals(1, s.winsA());
        assertEquals(1, s.draws());
        assertEquals(1.0, s.winRateA(), 1e-9);
        assertEquals(30.0, s.avgTurns(), 1e-9);
        assertEquals(30.0, s.medianTurns(), 1e-9);
    }

    @Test
    void simDefekteSpieleZaehlenGetrennt() {
        GameRecord broken = g(1, "B", 16).withSimErrors(3);
        assertTrue(broken.simBroken());
        var s = BenchStats.summarize(List.of(g(0, "A", 20), broken));
        assertEquals(1, s.simBroken());
        assertEquals(0, s.winsB());
        assertEquals(1, s.winsA());
        assertEquals(20.0, s.avgTurns(), 1e-9);
    }

    /** "Nichtstun"-Spiel (docs/bench/2026-09-20-stufe-2-nichtstun.md): ein Sitz spielt mindestens 5 Laender,
     *  wirkt aber hoechstens 2 Zauber. Gezaehlt aus Forges Logzeilen "<Sitz> played <Land>" und
     *  "<Sitz> cast <Zauber>" (GameEventLandPlayed/GameEventSpellAbilityCast); "activated"/"triggered"
     *  zaehlen nicht, und ein Kartentext mit " cast " mitten in der Zeile auch nicht. */
    @Test
    void activityCounterErkenntNichtstunJeSitz() {
        ActivityCounter c = new ActivityCounter();
        for (int i = 0; i < 5; i++) {
            c.see("A played Forest (" + i + ")");
            c.see("B played Island (" + i + ")");
        }
        c.see("A cast Grizzly Bears");
        c.see("A cast Llanowar Elves");
        c.see("A activated Llanowar Elves - Mana ability");
        c.see("Path of Ancestry (144) - {T}: Add one mana. When that mana is spent to cast a creature spell, scry 1.");
        for (int i = 0; i < 3; i++) {
            c.see("B cast Counterspell");
        }
        assertEquals("A", c.fewSpells());

        ActivityCounter fewLands = new ActivityCounter();
        for (int i = 0; i < 4; i++) {
            fewLands.see("A played Forest (" + i + ")");
        }
        assertEquals("", fewLands.fewSpells(), "unter 5 Laendern ist es Landmangel, kein Nichtstun");

        ActivityCounter both = new ActivityCounter();
        for (int i = 0; i < 6; i++) {
            both.see("A played Forest (" + i + ")");
            both.see("B played Forest (" + i + ")");
        }
        assertEquals("AB", both.fewSpells());
    }

    @Test
    void summaryZaehltNichtstunSpieleJeSitz() {
        var s = BenchStats.summarize(List.of(g(0, "A", 20).withFewSpells("B"), g(1, "B", 30).withFewSpells("AB"),
                g(2, "A", 40), GameRecord.crash(3, 4, "?", new RuntimeException("x"), 5000).withFewSpells("A")));
        assertEquals(1, s.fewSpellsA(), "Abstuerze zaehlen nicht");
        assertEquals(2, s.fewSpellsB());
        assertTrue(g(0, "A", 20).withFewSpells("AB").fewSpells("A"));
        assertTrue(g(0, "A", 20).withFewSpells("AB").fewSpells("B"));
        assertTrue(!g(0, "A", 20).fewSpells("A"));
    }

    /** Vier Sitze A1,A2,B1,B2: gezaehlt werden Sitze je Team, nicht Spiele mit Treffer - sonst zaehlte ein Team,
     *  in dem beide Sitze nichts tun, nur einmal. Das Zwei-Sitze-Format ("A", "B", "AB") bleibt wie bisher. */
    @Test
    void summaryZaehltNichtstunSitzeJeTeamExakt() {
        var vier = BenchStats.summarize(List.of(
                g(0, "A", 20).withFewSpells("A1B2"),
                g(1, "B", 30).withFewSpells("A1A2"),
                g(2, "A", 40).withFewSpells("B1"),
                g(3, "A", 40).withFewSpells("A1A2B1B2")));
        assertEquals(1 + 2 + 0 + 2, vier.fewSpellsA(), "beide Sitze eines Teams zaehlen doppelt");
        assertEquals(1 + 0 + 1 + 2, vier.fewSpellsB());

        var zwei = BenchStats.summarize(List.of(g(0, "A", 20).withFewSpells("AB"), g(1, "B", 30).withFewSpells("B")));
        assertEquals(1, zwei.fewSpellsA());
        assertEquals(2, zwei.fewSpellsB());

        assertEquals(2, g(0, "A", 20).withFewSpells("A1A2").fewSpellsSitze("A"));
        assertEquals(0, g(0, "A", 20).withFewSpells("A1A2").fewSpellsSitze("B"));
        assertEquals(0, g(0, "A", 20).fewSpellsSitze("A"), "null-sicher");
        assertTrue(g(0, "A", 20).withFewSpells("A1B2").fewSpells("B"));
        assertTrue(!g(0, "A", 20).withFewSpells("A1A2").fewSpells("B"));
    }
}
