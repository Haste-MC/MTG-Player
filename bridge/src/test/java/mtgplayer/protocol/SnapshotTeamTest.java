package mtgplayer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.game.GameView;
import forge.game.player.PlayerView;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Der Snapshot traegt eine KOPIE der Teamzuordnung (PlayerView kennt kein Team). Dieser Test haelt sie
 *  gegen die Engine - Sitz fuer Sitz, in einer echten Spielstellung. */
class SnapshotTeamTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void snapshotTeamGleichEngineTeam() {
        List<Integer> teams = List.of(1, 1, 2, 2);
        Scene s = Scene.ofTeams(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                teams, 3);
        GameView gv = s.game().getView();
        List<Integer> ids = gv.getPlayers().stream().map(PlayerView::getId).toList();
        ViewContext ctx = new ViewContext(null, WebGuiGame.teamsById(teams, ids),
                c -> true, c -> false, c -> false, c -> false, e -> false, p -> false,
                Snapshot.PromptSnap.EMPTY, new Messages.StopsMsg(List.of(), List.of()), false, true);

        Snapshot snap = StateSerializer.snapshot(gv, ctx);

        assertEquals(teams.size(), snap.players().size(), "ein PlayerSnap je Sitz");
        // Dass die Anzeige nicht vertauscht ist, sichert die Asymmetrie von 1/1/2/2: eine umgekehrte oder
        // rotierte Zuordnung wuerde mindestens einen Sitz falsch benennen und den Vergleich unten brechen.
        for (int i = 0; i < teams.size(); i++) {
            assertEquals(s.player(i).getTeam(), snap.players().get(i).team(),
                    "Sitz " + i + ": Anzeige-Kopie und Engine muessen dasselbe Team nennen");
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void ausgeschiedenerSitzVerschiebtDieZuordnungDerUeberlebendenNicht() {
        List<Integer> teams = List.of(1, 1, 2, 2);
        Scene s = Scene.ofTeams(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                teams, 3);
        s.player(2).setLife(0, null);                // Sitz 2 (Team 2) scheidet aus, Sitz 3 (Team 2) lebt
        s.step(1);
        assertTrue(s.player(2).hasLost(), "Sitz 2 ist ausgeschieden");
        assertFalse(s.game().isGameOver(), "der Partner haelt das Team im Spiel, die Partie laeuft weiter");

        GameView gv = s.game().getView();
        List<Integer> ids = gv.getPlayers().stream().map(PlayerView::getId).toList();
        assertEquals(teams.size(), ids.size(), "Forge kuerzt die Sitzliste beim Ausscheiden nicht - sonst "
                + "wuerde teamsById die Teams stillschweigend verwerfen");
        ViewContext ctx = new ViewContext(null, WebGuiGame.teamsById(teams, ids),
                c -> true, c -> false, c -> false, c -> false, e -> false, p -> false,
                Snapshot.PromptSnap.EMPTY, new Messages.StopsMsg(List.of(), List.of()), false, true);

        Snapshot snap = StateSerializer.snapshot(gv, ctx);

        assertEquals(teams.size(), snap.players().size(), "auch der ausgeschiedene Sitz hat seinen PlayerSnap");
        for (int i = 0; i < teams.size(); i++) {
            assertEquals(teams.get(i), snap.players().get(i).team(),
                    "Sitz " + i + " traegt nach dem Ausscheiden von Sitz 2 weiter sein eigenes Team");
            assertEquals(s.player(i).getTeam(), snap.players().get(i).team(),
                    "Sitz " + i + ": Anzeige-Kopie und Engine nennen auch jetzt dasselbe Team");
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void teamFeldStehtImJsonNurBeiTeamPartien() {
        Scene s = Scene.ofTeams(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), 3);
        GameView gv = s.game().getView();
        List<Integer> ids = gv.getPlayers().stream().map(PlayerView::getId).toList();
        Messages.StopsMsg stops = new Messages.StopsMsg(List.of(), List.of());
        ViewContext mit = new ViewContext(null, WebGuiGame.teamsById(List.of(1, 1, 2, 2), ids),
                c -> true, c -> false, c -> false, c -> false, e -> false, p -> false,
                Snapshot.PromptSnap.EMPTY, stops, false, true);
        ViewContext ohne = new ViewContext(null, WebGuiGame.teamsById(null, ids),
                c -> true, c -> false, c -> false, c -> false, e -> false, p -> false,
                Snapshot.PromptSnap.EMPTY, stops, false, true);

        JsonNode seatMit = Json.parse(Json.toJson(StateSerializer.snapshot(gv, mit))).get("players").get(0);
        JsonNode seatOhne = Json.parse(Json.toJson(StateSerializer.snapshot(gv, ohne))).get("players").get(0);

        assertFalse(seatOhne.has("team"),
                "ohne Teams fehlt das Feld ganz - das JSON bleibt fuer aeltere Clients byte-gleich");
        assertEquals(1, seatMit.get("team").asInt(), "mit Teams steht die Nummer im JSON");
    }
}
