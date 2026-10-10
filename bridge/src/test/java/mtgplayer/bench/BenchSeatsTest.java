package mtgplayer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mtgplayer.ai.AiConfig;
import org.junit.jupiter.api.Test;

/** Die Besetzung eines Bench-Spiels: ohne --teams wie bisher zwei Sitze mit Wechsel je Spiel, mit --teams
 *  vier Sitze, gespiegeltes Deckpaar und abwechselnde Teams. */
class BenchSeatsTest {

    /** Zwei erkennbar verschiedene Einstellungen; direkt gebaut statt ueber {@code BenchArgs.parse}, damit
     *  der Test ohne {@code ForgeBoot.init()} auskommt (Profilpruefung braucht Forge). */
    private static final AiConfig SIM = new AiConfig(AiConfig.Mode.SIM, "Default");
    private static final AiConfig STD = AiConfig.DEFAULT;

    private static BenchArgs args(String extra) {
        return new BenchArgs(4, SIM, STD, "precon:P", "precon:Q", 200, 5, 1L, Path.of("bench-test"), 30, true,
                extra.contains("--teams"));
    }

    private static List<String> specs(Bench.Besetzung b) {
        return b.configs().stream().map(c -> c.equals(SIM) ? "sim" : c.equals(STD) ? "std" : "?").toList();
    }

    @Test
    void ohneTeamsZweiSitzeMitWechsel() {
        assertEquals(List.of("A", "B"), Bench.besetzung(args(""), 0).namen());
        assertEquals(List.of("B", "A"), Bench.besetzung(args(""), 1).namen(), "jedes zweite Spiel gedreht");
        assertEquals(List.of(-1, -1), Bench.besetzung(args(""), 0).teams());
    }

    @Test
    void ohneTeamsWandernDeckUndKiMitDemSitz() {
        Bench.Besetzung gerade = Bench.besetzung(args(""), 0);
        assertEquals(List.of("precon:P", "precon:Q"), gerade.deckRefs());
        assertEquals(List.of("sim", "std"), specs(gerade));

        Bench.Besetzung ungerade = Bench.besetzung(args(""), 1);
        assertEquals(List.of("precon:Q", "precon:P"), ungerade.deckRefs(), "Deck B sitzt vorn, wenn B vorn sitzt");
        assertEquals(List.of("std", "sim"), specs(ungerade));
    }

    @Test
    void mitTeamsVierSitzeGespiegelt() {
        Bench.Besetzung b = Bench.besetzung(args("--teams"), 0);

        assertEquals(List.of("A1", "B1", "A2", "B2"), b.namen(), "abwechselnd");
        assertEquals(List.of(1, 2, 1, 2), b.teams());
        assertEquals(List.of("precon:P", "precon:P", "precon:Q", "precon:Q"), b.deckRefs(),
                "beide Teams spielen dasselbe Deckpaar, benachbarte Sitze dasselbe Deck");
        assertEquals(List.of("sim", "std", "sim", "std"), specs(b),
                "je Team eine Einstellung, auf beiden Sitzen des Teams");
    }

    @Test
    void mitTeamsRotiertDerErsteSitzUeberDieSpiele() {
        List<String> ersteSitze = List.of(0, 1, 2, 3).stream()
                .map(i -> Bench.besetzung(args("--teams"), i).namen().get(0)).toList();

        assertEquals(4, ersteSitze.stream().distinct().count(),
                "ueber vier Spiele faengt jeder Sitz einmal an, sonst klebt der Startvorteil an einem Team: " + ersteSitze);
    }

    @Test
    void mitTeamsBeideTeamsHabenInJedemSpielDasselbeDeckpaar() {
        // Das ist die Zusicherung der Messung: waere ein Team auf beide Kopien desselben Decks gesetzt,
        // sagte der Unterschied im Ergebnis nichts ueber die KI-Einstellung.
        for (int i = 0; i < 8; i++) {
            Bench.Besetzung b = Bench.besetzung(args("--teams"), i);
            List<String> teamA = new ArrayList<>();
            List<String> teamB = new ArrayList<>();
            for (int s = 0; s < 4; s++) {
                (b.teams().get(s) == 1 ? teamA : teamB).add(b.deckRefs().get(s));
            }
            teamA.sort(null);
            teamB.sort(null);
            assertEquals(List.of("precon:P", "precon:Q"), teamA, "Spiel " + i + ": Team A hat beide Decks");
            assertEquals(List.of("precon:P", "precon:Q"), teamB, "Spiel " + i + ": Team B hat beide Decks");
        }
    }

    @Test
    void mitTeamsGehoertJederSitzImmerZuSeinemTeamUndSeinerKi() {
        for (int i = 0; i < 8; i++) {
            Bench.Besetzung b = Bench.besetzung(args("--teams"), i);
            for (int s = 0; s < 4; s++) {
                boolean teamA = b.namen().get(s).startsWith("A");
                assertEquals(teamA ? 1 : 2, b.teams().get(s), "Spiel " + i + " Sitz " + s + ": Name und Team");
                assertEquals(teamA ? SIM : STD, b.configs().get(s), "Spiel " + i + " Sitz " + s + ": KI");
            }
            assertEquals(List.of(b.teams().get(0), 3 - b.teams().get(0), b.teams().get(0), 3 - b.teams().get(0)),
                    b.teams(), "Spiel " + i + ": die Teams wechseln sich ab");
        }
    }

    @Test
    void mitTeamsRotiertAuchDasTeamVonSitzNull() {
        List<Integer> vorn = List.of(0, 1, 2, 3).stream()
                .map(i -> Bench.besetzung(args("--teams"), i).teams().get(0)).toList();

        assertEquals(List.of(1, 2, 1, 2), vorn, "beide Teams eroeffnen gleich oft");
    }

    @Test
    void mitTeamsWiederholtSichDieBesetzungNachVierSpielen() {
        assertEquals(Bench.besetzung(args("--teams"), 1), Bench.besetzung(args("--teams"), 5));
    }

    @Test
    void siegerTeamWirdAusDemSitznamenAbgeleitet() {
        assertEquals("A", Bench.siegerTeam("A1", true));
        assertEquals("B", Bench.siegerTeam("B2", true));
        assertNull(Bench.siegerTeam(null, true), "Unentschieden bleibt Unentschieden");
        assertEquals("A", Bench.siegerTeam("A", false), "ohne Teams bleibt der Sitzname stehen");
        assertNull(Bench.siegerTeam(null, false));
    }
}
