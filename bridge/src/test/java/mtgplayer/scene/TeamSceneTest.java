package mtgplayer.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.game.GameEndReason;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.match.TestSeats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Vier Sitze, Teams 1/1/2/2: Partner sind keine Gegner, und sobald ein Team komplett draussen ist,
 *  endet die Partie mit {@code AllOpposingTeamsLost} und dem anderen Team als Sieger. */
class TeamSceneTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static Scene vierSitzeZweiTeams() {
        return Scene.ofTeams(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), 3);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void partnerIstKeinGegner() {
        Scene s = vierSitzeZweiTeams();
        assertFalse(s.player(0).isOpponentOf(s.player(1)), "Sitz 0 und 1 sind ein Team");
        assertTrue(s.player(0).isOpponentOf(s.player(2)), "Sitz 2 ist im anderen Team");
        assertEquals(1, s.player(1).getTeam());
        assertEquals(2, s.player(3).getTeam());
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void einTeamVerliertGemeinsam() {
        Scene s = vierSitzeZweiTeams();
        for (Player p : List.of(s.player(2), s.player(3))) {
            p.setLife(0, null);
        }
        s.step(1);                                   // eine Zustandspruefung (SBA) reicht
        assertTrue(s.game().isGameOver(), "zwei Sitze auf 0 Leben beenden die Partie");
        assertEquals(GameEndReason.AllOpposingTeamsLost, s.game().getOutcome().getWinCondition());
        assertEquals(1, s.game().getOutcome().getWinningTeam());
        assertTrue(s.game().getOutcome().isWinner(s.player(0).getRegisteredPlayer()), "Sitz 0 gewinnt mit");
        assertTrue(s.game().getOutcome().isWinner(s.player(1).getRegisteredPlayer()), "Sitz 1 gewinnt mit");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void einLebenderPartnerHaeltDasTeamAmLeben() {
        Scene s = vierSitzeZweiTeams();
        s.player(2).setLife(0, null);                // nur Sitz 2 stirbt, Sitz 3 (gleiches Team) lebt
        s.step(1);
        assertFalse(s.game().isGameOver(),
                "solange ein Partner lebt, ist das Team nicht draussen - die Partie laeuft weiter");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void dieZugreihenfolgeWechseltZwischenDenTeams() {
        // Lobby-Reihenfolge 1,1,2,2 - so kommt die Teamliste aus der Lobby. Die Sitze baut die
        // Produktion (AiMatch.registered -> Seating.interleave); der Test tippt die Reihenfolge nicht
        // selbst ein, sonst bliebe er gruen, auch wenn das Umsortieren aus der Produktion verschwaende.
        List<Deck> decks = List.of(new Deck("D0"), new Deck("D1"), new Deck("D2"), new Deck("D3"));
        List<RegisteredPlayer> sitze = TestSeats.headless(decks, List.of("A1", "A2", "B1", "B2"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2));

        Scene s = Scene.ofSeats(sitze, 3);

        // Die Zugreihenfolge der Engine folgt den Sitzen; gelesen wird sie aus dem echten Spiel.
        List<Integer> reihenfolge = s.game().getPlayers().stream().map(Player::getTeam).toList();
        for (int i = 1; i < reihenfolge.size(); i++) {
            assertNotEquals(reihenfolge.get(i - 1), reihenfolge.get(i),
                    "Sitz " + i + " zieht direkt nach seinem eigenen Team: " + reihenfolge);
        }
        assertEquals(List.of("A1", "B1", "A2", "B2"),
                s.game().getPlayers().stream().map(Player::getName).toList(),
                "abwechselnd, Sitz 0 bleibt vorn - und die Namen sind mit ihren Teams mitgewandert");
    }
}
