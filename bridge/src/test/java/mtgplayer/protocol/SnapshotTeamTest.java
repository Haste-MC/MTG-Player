package mtgplayer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        // Gegenprobe gegen eine Zuordnung, die nur zufaellig stimmt: der Engine-Wert selbst darf nicht -1 sein.
        assertNotEquals(-1, s.player(0).getTeam(), "die Engine kennt die Teams der Stellung");
        for (int i = 0; i < teams.size(); i++) {
            assertEquals(s.player(i).getTeam(), snap.players().get(i).team(),
                    "Sitz " + i + ": Anzeige-Kopie und Engine muessen dasselbe Team nennen");
        }
    }
}
