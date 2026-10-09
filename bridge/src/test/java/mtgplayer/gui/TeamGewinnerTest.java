package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.GameView;
import forge.game.player.PlayerView;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wer am Ende als Sieger im Dialog steht. In einer Team-Partie bleiben zwei Sitze "nicht verloren"
 * uebrig - ohne Team-Sicht galte das als Unentschieden, obwohl der Datensatz die Sieger nennt.
 */
class TeamGewinnerTest {

    private static final List<Integer> TEAMS = List.of(1, 1, 2, 2);

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    // --- die reine Entscheidung: Ueberlebende gegen Zuordnung ---

    @Test
    void zweiUeberlebendeAusEinemTeamGewinnenGemeinsam() {
        assertEquals("Team 1", WebGuiGame.teamWinner(List.of(7, 8), Map.of(7, 1, 8, 1, 9, 2)));
    }

    @Test
    void zweiUeberlebendeAusVerschiedenenTeamsSindKeinSieger() {
        assertNull(WebGuiGame.teamWinner(List.of(7, 9), Map.of(7, 1, 8, 1, 9, 2)),
                "zwei Teams noch im Spiel ist ein Unentschieden, kein Teamsieg");
    }

    @Test
    void ohneTeamsEntscheidetWieBisherDerEinzigeUeberlebende() {
        assertNull(WebGuiGame.teamWinner(List.of(7, 8), Map.of()));
    }

    @Test
    void einUeberlebenderMitTeamGewinntFuerSeinTeam() {
        assertEquals("Team 2", WebGuiGame.teamWinner(List.of(9), Map.of(7, 1, 8, 1, 9, 2)));
    }

    @Test
    void niemandUebrigIstKeinSieger() {
        assertNull(WebGuiGame.teamWinner(List.of(), Map.of(7, 1)));
    }

    @Test
    void sitzOhneTeamEintragIstKeinTeamsieg() {
        assertNull(WebGuiGame.teamWinner(List.of(9), Map.of(7, 1)), "unbekannter Sitz: lieber nichts behaupten");
        assertNull(WebGuiGame.teamWinner(List.of(7), Map.of(7, -1)), "Team -1 heisst: kein Team");
    }

    // --- gegen eine echte Stellung: GameView, hasLost, Sitz-Ids ---

    private static Scene vierSitze() {
        return Scene.ofTeams(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                TEAMS, 3);
    }

    private static Map<Integer, Integer> teamsDerAnsicht(GameView gv) {
        return WebGuiGame.teamsById(TEAMS, gv.getPlayers().stream().map(PlayerView::getId).toList());
    }

    private static List<Integer> ids(GameView gv, int... plaetze) {
        List<PlayerView> alle = List.copyOf(gv.getPlayers());
        return java.util.Arrays.stream(plaetze).mapToObj(i -> alle.get(i).getId()).toList();
    }

    private static void ausscheiden(Scene s, int... plaetze) {
        for (int i : plaetze) {
            s.player(i).setLife(0, null);
        }
        s.step(1);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void beideSitzeDesGewinnerteamsStehenImAusgang() {
        Scene s = vierSitze();
        ausscheiden(s, 2, 3);
        GameView gv = s.game().getView();

        WebGuiGame.Ausgang a = WebGuiGame.ausgang(gv, teamsDerAnsicht(gv));

        assertEquals("Team 1", a.winner());
        assertEquals(ids(gv, 0, 1), a.sitze());
        assertEquals("Team 1", WebGuiGame.gewinner(gv, teamsDerAnsicht(gv)));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void einUeberlebenderInDerTeamPartieGewinntFuerSeinTeam() {
        Scene s = vierSitze();
        ausscheiden(s, 1, 2, 3);                     // der Partner ist auch draussen, Sitz 0 allein uebrig
        GameView gv = s.game().getView();

        WebGuiGame.Ausgang a = WebGuiGame.ausgang(gv, teamsDerAnsicht(gv));

        assertEquals("Team 1", a.winner(), "ein Team gewinnt auch, wenn nur einer von beiden uebrig ist");
        assertEquals(ids(gv, 0), a.sitze());
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void ueberlebendeAusVerschiedenenTeamsGebenKeinenSieger() {
        Scene s = vierSitze();
        ausscheiden(s, 1, 3);                        // Sitz 0 (Team 1) und Sitz 2 (Team 2) leben
        GameView gv = s.game().getView();

        WebGuiGame.Ausgang a = WebGuiGame.ausgang(gv, teamsDerAnsicht(gv));

        assertNull(a.winner(), "zwei Teams uebrig: Unentschieden");
        assertTrue(a.sitze().isEmpty(), "ohne Sieger eine leere Liste, nicht null");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void zweiUeberlebendeOhneTeamsBleibenEinUnentschieden() {
        Scene s = vierSitze();
        ausscheiden(s, 1, 3);
        GameView gv = s.game().getView();

        WebGuiGame.Ausgang a = WebGuiGame.ausgang(gv, Map.of());

        assertNull(a.winner(), "ohne Teams aendert sich nichts: mehrere Ueberlebende sind kein Sieger");
        assertTrue(a.sitze().isEmpty());
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void einUeberlebenderOhneTeamsIstDerSiegerMitSeinemSitz() {
        Scene s = vierSitze();
        ausscheiden(s, 1, 2, 3);
        GameView gv = s.game().getView();

        WebGuiGame.Ausgang a = WebGuiGame.ausgang(gv, Map.of());

        PlayerView sieger = List.copyOf(gv.getPlayers()).get(0);
        assertEquals(sieger.getName(), a.winner());
        assertEquals(List.of(sieger.getId()), a.sitze());
    }

    @Test
    void keineAnsichtIstKeinSieger() {
        WebGuiGame.Ausgang a = WebGuiGame.ausgang(null, Map.of());
        assertNull(a.winner());
        assertTrue(a.sitze().isEmpty());
    }

    @Test
    void gameOverNachrichtTraegtDieSitzeImJson() {
        String json = Json.toJson(new Messages.GameOver("Team 1", List.of(3, 4)));
        assertEquals("gameOver", Json.parse(json).get("type").asText());
        assertEquals("Team 1", Json.parse(json).get("winner").asText());
        assertEquals(3, Json.parse(json).get("winnerSeats").get(0).asInt());
        assertEquals(0, Json.parse(Json.toJson(new Messages.GameOver(null, List.of()))).get("winnerSeats").size(),
                "ohne Sieger eine leere Liste im JSON");
    }
}
