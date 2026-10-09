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
                {"spectate":true,"opponents":[{"precon":"A","team":1},{"precon":"B","team":2}]}""");
        assertEquals(List.of(1, 2), Teams.parse(m, true, 2));
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
    void nummerAusserhalbEinsBisSechs() {
        JsonNode m = msg("""
                {"humanTeam":0,"opponents":[{"precon":"B","team":0},{"precon":"C","team":2}]}""");
        assertThrows(IllegalArgumentException.class, () -> Teams.parse(m, false, 2));
    }
}
