package mtgplayer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import mtgplayer.ai.AiConfig;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;

/** Der Bench-Bericht (Markdown und JSON): mit {@code --teams} nennt er Aufstellung und Teams, ohne bleibt er
 *  Zeichen fuer Zeichen wie vorher. Direkt ueber den Konstruktor gebaut, weil {@code BenchArgs.parse} Forge braucht. */
class BenchReportTest {
    private static final AiConfig SIM = new AiConfig(AiConfig.Mode.SIM, "Default");
    private static final AiConfig STD = AiConfig.DEFAULT;

    private static BenchArgs args(boolean teams) {
        return new BenchArgs(2, SIM, STD, "precon:P", "precon:Q", 200, 5, 7L, Path.of("bench-test"), 30, true, teams);
    }

    private static List<GameRecord> spiele() {
        return List.of(
                new GameRecord(0, 1L, "A1", "A", "AllOpposingTeamsLost", 20, 1000, false),
                new GameRecord(1, 2L, "B1", "B", "AllOpposingTeamsLost", 24, 1000, false));
    }

    private static String ohneZeitstempel(String md) {
        return md.lines().map(z -> z.startsWith("Decks:") ? z.replaceAll(" · \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$", "") : z)
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }

    @Test
    void berichtNenntDieAufstellungUndDieTeams() {
        List<GameRecord> spiele = spiele();
        String md = Bench.markdownFuerTest(args(true), spiele, BenchStats.summarize(spiele));

        assertTrue(md.contains("2v2"), "die Kopfzeile sagt, dass es eine Teampartie war:\n" + md);
        assertTrue(md.startsWith("# Bench 2v2 sim:Default vs standard:Default\n"), "Titelzeile:\n" + md);
        assertTrue(md.contains("Deckpaar (beide Teams): precon:P + precon:Q · Spiele: 2"), "Deckzeile:\n" + md);
        assertTrue(md.contains("| | Team A | Team B | Unentschieden | Abstürze | Sim defekt |"), "Tabellenkopf:\n" + md);
        assertTrue(md.contains("Siegquote Team A (entschiedene Spiele): 50,0 %"), "Siegquote:\n" + md);
        assertTrue(md.contains("precon:P") && md.contains("precon:Q"), "das Deckpaar steht im Kopf");
    }

    @Test
    void ohneTeamsBleibtDerBerichtWieBisher() {
        List<GameRecord> spiele = spiele();
        String md = ohneZeitstempel(Bench.markdownFuerTest(args(false), spiele, BenchStats.summarize(spiele)));
        String erwartet = String.join("\n",
                "# Bench sim:Default vs standard:Default",
                "Decks: A = precon:P, B = precon:Q · Spiele: 2 · Zugdeckel: 200 · Timeout: 5 s · Seed: 7",
                "| | A | B | Unentschieden | Abstürze | Sim defekt |",
                "|---|---|---|---|---|---|",
                "| Siege | 1 | 1 | 0 (Zugdeckel 0) | 0 | 0 |",
                "Siegquote A (entschiedene Spiele): 50,0 % · 95-%-Intervall 9,5–90,5 % · Ø Züge 22,0 (Median 22) · Ø Dauer 1 s"
                        + " · Nichtstun (≥ 5 Länder, ≤ 2 Zauber) A 0 / B 0",
                "## Spiele",
                "| # | Seed | Erster | Sieger | Grund | Züge | Dauer s | Sim-Fehler | Nichtstun |",
                "|---|---|---|---|---|---|---|---|---|",
                "| 1 | 1 | A1 | A | AllOpposingTeamsLost | 20 | 1 | 0 | – |",
                "| 2 | 2 | B1 | B | AllOpposingTeamsLost | 24 | 1 | 0 | – |");
        assertEquals(erwartet, md.stripTrailing());
        assertFalse(md.contains("2v2") || md.contains("Team A") || md.contains("Team B"), md);
    }

    @Test
    void jsonNenntTeamsUndDieAufstellungJeSpiel() throws Exception {
        List<GameRecord> spiele = spiele();
        JsonNode j = Json.mapper().readTree(Bench.jsonFuerTest(args(true), spiele, BenchStats.summarize(spiele)));

        assertTrue(j.get("teams").asBoolean());
        JsonNode zweites = j.get("games").get(1);
        assertEquals("B", zweites.get("winner").asText(), "die bisherigen Felder bleiben, wo sie waren");
        JsonNode sitze = zweites.get("besetzung");
        assertEquals(4, sitze.size());
        assertEquals("B1", sitze.get(0).get("sitz").asText());
        assertEquals(2, sitze.get(0).get("team").asInt());
        assertEquals("A2", sitze.get(1).get("sitz").asText());
        assertEquals(1, sitze.get(1).get("team").asInt());
    }

    @Test
    void jsonOhneTeamsMeldetFalseUndZweiSitze() throws Exception {
        List<GameRecord> spiele = spiele();
        JsonNode j = Json.mapper().readTree(Bench.jsonFuerTest(args(false), spiele, BenchStats.summarize(spiele)));

        assertFalse(j.get("teams").asBoolean());
        assertEquals("B", j.get("games").get(1).get("besetzung").get(0).get("sitz").asText());
        assertEquals(2, j.get("games").get(1).get("besetzung").size());
    }
}
