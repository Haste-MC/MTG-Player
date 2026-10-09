package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

class TeamsTest {

    private static JsonNode msg(String json) {
        return Json.parse(json);
    }

    @Test
    void ohneAngabeKeineTeams() {
        JsonNode m = msg("""
                {"humanDeck":{"precon":"A"},"opponents":[{"precon":"B"},{"precon":"C"}]}""");
        assertNull(Teams.parse(m, false, 2), "ohne team-Feld bleibt es beim Jeder-gegen-jeden");
    }

    @Test
    void menschZuerstDannGegnerInReihenfolge() {
        JsonNode m = msg("""
                {"humanTeam":1,"humanDeck":{"precon":"A"},
                 "opponents":[{"precon":"B","team":1},{"precon":"C","team":2},{"precon":"D","team":2}]}""");
        assertEquals(List.of(1, 1, 2, 2), Teams.parse(m, false, 3));
    }

    @Test
    void zuschauerModusOhneMenschlichenSitz() {
        JsonNode m = msg("""
                {"spectate":true,"opponents":[{"precon":"A","team":1},{"precon":"B","team":1},{"precon":"C","team":2}]}""");
        // Drei KI-Sitze und ein Paar: zwei Sitze allein waeren seit der Regel "ein Team braucht zwei Sitze" ungueltig.
        assertEquals(List.of(1, 1, 2), Teams.parse(m, true, 3));
    }

    @Test
    void entwederAlleOderKeiner() {
        JsonNode m = msg("""
                {"humanTeam":1,"opponents":[{"precon":"B","team":1},{"precon":"C"}]}""");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Teams.parse(m, false, 2));
        assertTrue(e.getMessage().contains("alle Sitze"), e.getMessage());
    }

    @Test
    void mindestensZweiTeams() {
        JsonNode m = msg("""
                {"humanTeam":1,"opponents":[{"precon":"B","team":1},{"precon":"C","team":1}]}""");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Teams.parse(m, false, 2));
        assertTrue(e.getMessage().contains("zwei verschiedene"), e.getMessage());
    }

    @Test
    void jederSitzAlleinImTeamIstKeinTeamUndWirdAbgelehnt() {
        // Drei Sitze, drei Nummern: "mindestens zwei verschiedene" stimmt, aber niemand hat einen
        // Mitspieler. Der MatchRecorder schriebe das als Jeder-gegen-jeden in den Datensatz - die Lobby
        // darf es deshalb gar nicht erst als Team-Partie starten.
        JsonNode m = msg("""
                {"humanTeam":1,"opponents":[{"precon":"B","team":2},{"precon":"C","team":3}]}""");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Teams.parse(m, false, 2));
        assertTrue(e.getMessage().contains("ein Team braucht zwei Sitze"), e.getMessage());
    }

    @Test
    void einsGegenZweiUndZweiGegenZweiBleibenGueltig() {
        // Gegenprobe zur Ablehnung oben: ein einziges Zweier-Team genuegt, die uebrigen duerfen allein sein.
        JsonNode eins = msg("""
                {"humanTeam":1,"opponents":[{"precon":"B","team":2},{"precon":"C","team":2}]}""");
        assertEquals(List.of(1, 2, 2), Teams.parse(eins, false, 2));
        JsonNode zwei = msg("""
                {"humanTeam":1,"opponents":[{"precon":"B","team":1},{"precon":"C","team":2},{"precon":"D","team":2}]}""");
        assertEquals(List.of(1, 1, 2, 2), Teams.parse(zwei, false, 3));
    }

    @Test
    void nummerAusserhalbEinsBisSechs() {
        // Die anderen Sitze sind gueltig und verschieden, damit nur die Bereichspruefung greifen kann.
        JsonNode null0 = msg("""
                {"humanTeam":0,"opponents":[{"precon":"B","team":1},{"precon":"C","team":2}]}""");
        IllegalArgumentException e0 = assertThrows(IllegalArgumentException.class, () -> Teams.parse(null0, false, 2));
        assertTrue(e0.getMessage().contains("Nummer 1 bis 6, nicht 0"), e0.getMessage());

        JsonNode sieben = msg("""
                {"humanTeam":1,"opponents":[{"precon":"B","team":7},{"precon":"C","team":2}]}""");
        IllegalArgumentException e7 = assertThrows(IllegalArgumentException.class, () -> Teams.parse(sieben, false, 2));
        assertTrue(e7.getMessage().contains("Nummer 1 bis 6, nicht 7"), e7.getMessage());
    }

    @Test
    void minusEinsHeisstKeinTeam() {
        JsonNode alle = msg("""
                {"humanTeam":-1,"opponents":[{"precon":"B","team":-1},{"precon":"C","team":-1}]}""");
        assertNull(Teams.parse(alle, false, 2), "-1 auf allen Sitzen ist ein legaler Jeder-gegen-jeden-Start");

        JsonNode gemischt = msg("""
                {"humanTeam":1,"opponents":[{"precon":"B","team":-1},{"precon":"C","team":2}]}""");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Teams.parse(gemischt, false, 2));
        assertTrue(e.getMessage().contains("alle Sitze"), "-1 zaehlt als nicht gesetzt: " + e.getMessage());
    }

    @Test
    void sitzzahlOhneTeamsWirdNichtGeprueft() {
        JsonNode m = msg("""
                {"humanDeck":{"precon":"A"},"opponents":[{"precon":"B"}]}""");
        assertNull(Teams.parse(m, false, 3), "ohne Teams darf die Sitzzahl keine Ausnahme ausloesen");
    }

    @Test
    void sitzzahlMitTeamsWirdGeprueft() {
        JsonNode m = msg("""
                {"humanTeam":1,"opponents":[{"precon":"B","team":2}]}""");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Teams.parse(m, false, 3));
        assertTrue(e.getMessage().contains("3 Gegner erwartet, 1 gelesen"), e.getMessage());
    }

    @Test
    void nichtGanzzahligeTeamwerteWerdenAbgelehnt() {
        for (String wert : List.of("\"2\"", "true", "1.9", "2.0", "99999999999")) {
            JsonNode m = msg("{\"humanTeam\":1,\"opponents\":[{\"precon\":\"B\",\"team\":" + wert
                    + "},{\"precon\":\"C\",\"team\":2}]}");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Teams.parse(m, false, 2), "Wert " + wert + " haette abgelehnt werden muessen");
            assertTrue(e.getMessage().contains("Nummer 1 bis 6, nicht " + wert), e.getMessage());
        }
    }
}
