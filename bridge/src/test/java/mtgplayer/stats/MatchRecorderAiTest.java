package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.match.AiMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Integrationsnachweis: eine echte (kurze) KI-Partie ueber {@link AiMatch} liefert einen plausiblen
 * Datensatz. Bewusst keine exakten Zahlen - die haengen an den Entscheidungen der KI; geprueft wird,
 * dass der Recorder am echten Ereignisstrom haengt und nichts wirft.
 */
class MatchRecorderAiTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @BeforeEach
    void redirectCrashLog(@TempDir Path dir) {
        CrashLog.setFile(dir.resolve("logs").resolve("bridge.log"));
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setFile(null);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void kurzeKiPartieLiefertEinenDatensatz() throws Exception {
        List<String> precons = List.of("Abzan Armor [TDC] [2025]", "Adaptive Enchantment [C18] [2018]");
        List<Deck> decks = precons.stream().map(Precons::load).toList();
        List<MatchRecord> sunk = new ArrayList<>();

        AiMatch.Result r = AiMatch.play(decks, List.of("KI 1", "KI 2"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT), 3, 4, s -> { }, sunk::add);

        assertEquals(1, sunk.size(), "genau ein Datensatz je Partie");
        MatchRecord rec = sunk.get(0);
        assertEquals("sparring", rec.source());
        assertEquals(2, rec.seats().size());
        assertTrue(rec.turns() >= 1, "mindestens ein Zug, war " + rec.turns());
        assertTrue(Math.abs(rec.turns() - r.turns()) <= 1,
                "Zugzahl nahe an Forges eigenem Ergebnis: Recorder " + rec.turns() + ", Forge " + r.turns());
        assertTrue(rec.turns() <= 5, "Zugdeckel 4 eingehalten, war " + rec.turns());
        assertNotNull(rec.id());
        assertNotNull(rec.reason());
        // Mit maxTurns 4 laeuft die Partie praktisch immer in den Deckel; dann muss der Datensatz das
        // auch sagen. Endet sie ausnahmsweise von selbst, greift nur die uebliche "zu kurz"-Regel.
        if (r.turnCapped()) {
            assertFalse(rec.counted(), "eine am Zugdeckel abgeschnittene Partie zaehlt nicht");
            assertEquals("Zugdeckel", rec.excludeReason());
            assertTrue(rec.draw(), "AiMatch beendet den Deckel als Remis - der Ausgang bleibt stehen");
            assertTrue(rec.seats().stream().noneMatch(MatchRecord.Seat::winner), "ein Remis hat keinen Sieger");
        }

        int lands = 0;
        for (int i = 0; i < rec.seats().size(); i++) {
            MatchRecord.Seat s = rec.seats().get(i);
            assertEquals(precons.get(i), s.deck(), "Deckname des Sitzes " + i);
            assertEquals("KI " + (i + 1), s.name());
            assertNotNull(s.ai(), "KI-Sitz kennt seine Einstellungen");
            assertEquals("standard", s.ai().mode());
            assertTrue(s.landsByTurn().size() >= 1);
            assertEquals(s.lands(), s.landsByTurn().get(s.landsByTurn().size() - 1),
                    "landsByTurn ist kumulativ und endet bei der Gesamtzahl");
            lands += s.lands();
            pruefeVorfallKennzahlen(s);
        }
        assertTrue(lands > 0, "in vier Zuegen sollte mindestens ein Land liegen");
        assertEquals(2, rec.v(), "Runde B schreibt Formatversion 2");
        pruefeRobustheitsMeldung();
        System.out.println("[MatchRecorderAiTest] " + rec);
    }

    /**
     * {@code MatchRecord} traegt {@code counterFailures}/{@code castsNotOnStack} selbst nicht (siehe
     * {@code MatchRecorder} - AiMatch bleibt unveraendert, der Recorder ist nach der Partie nicht mehr
     * erreichbar), pruefbar bleibt aber das Log: ueber viele echte Ereignisse einer ganzen KI-Partie
     * hinweg darf die Sammelmeldung hoechstens EINMAL auftauchen, nicht einmal je Vorfall.
     */
    private static void pruefeRobustheitsMeldung() throws Exception {
        Path log = CrashLog.file();
        if (!Files.exists(log)) {
            return;                                    // nichts zu melden ist der haeufige, gute Fall
        }
        String text = Files.readString(log);
        int meldungen = text.split("MatchRecorder", -1).length - 1;
        assertTrue(meldungen <= 1, "hoechstens eine Sammelmeldung je Partie, nicht eine je Vorfall: " + text);
    }

    /**
     * Die Vorfall-Kennzahlen aus Runde B haengen an den Entscheidungen der KI, geprueft wird deshalb
     * nur, dass keine negativ ist und die Teilmengen-Beziehungen stimmen: gekonterte Zauber und
     * Counterspells sind Teilmengen der gewirkten Zauber, verlorene Kreaturen eine Teilmenge der
     * verlorenen bleibenden Karten, und ein Fenster mit Massenentfernung kann es nur geben, wenn
     * ueberhaupt genug verloren ging.
     */
    private static void pruefeVorfallKennzahlen(MatchRecord.Seat s) {
        for (int n : new int[]{s.spellsCountered(), s.spellsFizzled(), s.counterspellsCast(),
                s.cardsDrawn(), s.cardsDiscarded(), s.cardsMilled(), s.permanentsLost(),
                s.creaturesLostInCombat(), s.creaturesLostOther(), s.biggestSweep(),
                s.sweepsSuffered(), s.tokensCreated()}) {
            assertTrue(n >= 0, "kein Zaehler darf negativ werden: " + s);
        }
        assertTrue(s.counterspellsCast() <= s.spells(),
                "Counterspells sind gewirkte Zauber: " + s.counterspellsCast() + " von " + s.spells());
        assertTrue(s.creaturesLostInCombat() + s.creaturesLostOther() <= s.permanentsLost(),
                "verlorene Kreaturen sind eine Teilmenge der verlorenen bleibenden Karten: " + s);
        assertTrue(s.biggestSweep() <= s.permanentsLost(),
                "ein Fenster kann nicht mehr fassen als insgesamt verloren ging: " + s);
        assertTrue(s.sweepsSuffered() * MatchRecorder.SWEEP_MIN <= s.permanentsLost(),
                "jede Massenentfernung kostet mindestens " + MatchRecorder.SWEEP_MIN + ": " + s);
        pruefeKampfUndZeitachse(s);
    }

    /**
     * Kampf, Schaden und Zeitachse aus Stueck 2 - auch hier nur, was unabhaengig von den
     * KI-Entscheidungen gelten MUSS: die drei Kampfschaden-Toepfe ergeben zusammen den
     * Kampfschaden, mit dem Nicht-Kampfschaden den Gesamtschaden, und ausgeteilter Schaden teilt
     * sich genauso auf. Commander-Schaden liegt quer dazu und kann hoechstens so gross wie der
     * Kampfschaden sein. Die Zeitachse hat je eigenem Zug einen Punkt (gedeckelt) mit streng
     * steigenden Zugnummern.
     */
    private static void pruefeKampfUndZeitachse(MatchRecord.Seat s) {
        for (int n : new int[]{s.attacksDeclared(), s.attackedTurns(), s.attackersFaced(),
                s.blocksDeclared(), s.damageTakenFlying(), s.damageTakenTrample(), s.damageTakenOther(),
                s.damageTakenNonCombat(), s.damageDealtCombat(), s.damageDealtNonCombat(),
                s.commanderDamageTaken(), s.lifeGained()}) {
            assertTrue(n >= 0, "kein Zaehler darf negativ werden: " + s);
        }
        assertEquals(s.combatDamageTaken(), s.damageTakenFlying() + s.damageTakenTrample() + s.damageTakenOther(),
                "die drei Kampfschaden-Toepfe ergeben den Kampfschaden: " + s);
        assertEquals(s.damageTaken(), s.combatDamageTaken() + s.damageTakenNonCombat(),
                "Kampf- und Nicht-Kampfschaden ergeben den Gesamtschaden: " + s);
        assertEquals(s.damageDealt(), s.damageDealtCombat() + s.damageDealtNonCombat(),
                "ausgeteilter Schaden teilt sich genauso auf: " + s);
        assertTrue(s.commanderDamageTaken() <= s.combatDamageTaken(),
                "Commander-Schaden ist Kampfschaden, kein vierter Topf: " + s);
        assertTrue(s.attackedTurns() <= s.attacksDeclared(),
                "ein Angriffszug braucht mindestens einen Angreifer: " + s);
        assertNotNull(s.timeline(), "die Zeitachse ist nie null");
        assertTrue(s.timeline().size() <= MatchRecorder.TIMELINE_MAX,
                "die Zeitachse ist gedeckelt: " + s.timeline().size());
        assertEquals(Math.min(MatchRecorder.TIMELINE_MAX, s.landsByTurn().size() - 1), s.timeline().size(),
                "je eigenem Zug ein Punkt, wie bei landsByTurn: " + s);
        int last = 0;
        for (MatchRecord.TurnPoint p : s.timeline()) {
            assertTrue(p.turn() > last, "Zugnummern steigen: " + s.timeline());
            last = p.turn();
            assertTrue(p.lands() >= 0 && p.creatures() >= 0 && p.hand() >= 0, "kein negativer Stand: " + p);
        }
    }
}
