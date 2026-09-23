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
    void kurzeKiPartieLiefertEinenDatensatz() {
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
        }
        assertTrue(lands > 0, "in vier Zuegen sollte mindestens ein Land liegen");
        System.out.println("[MatchRecorderAiTest] " + rec);
    }
}
