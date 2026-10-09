package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code HumanMatch.applyTeams} ist die einzige Stelle, die Teamnummern aus der Nachricht in Forges
 * {@code RegisteredPlayer} schreibt. {@code TeamSceneTest} beweist nur das Verhalten der Engine bei
 * gesetzten Teams - ohne diesen Test bliebe alles gruen, wenn jemand den Aufruf in {@code start}/
 * {@code startSpectator} loescht und die Teams stillschweigend tot waeren. Bewusst ohne ForgeBoot und
 * ohne Spiel: blosse {@code RegisteredPlayer}-Objekte, laeuft in Millisekunden.
 */
class HumanMatchTeamsTest {

    private static List<RegisteredPlayer> sitze(int n) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            players.add(new RegisteredPlayer(new Deck()));
        }
        return players;
    }

    @Test
    void ohneTeamlisteBleibtForgesVorgabe() {
        List<RegisteredPlayer> players = sitze(3);
        HumanMatch.applyTeams(players, null);
        for (RegisteredPlayer rp : players) {
            assertEquals(-1, rp.getTeamNumber(), "null = Jeder gegen jeden, Forges Vorgabe -1 bleibt");
        }
    }

    @Test
    void passendeListeSetztDieNummernInSitzreihenfolge() {
        List<RegisteredPlayer> players = sitze(4);
        HumanMatch.applyTeams(players, List.of(1, 1, 2, 2));
        assertEquals(1, players.get(0).getTeamNumber());
        assertEquals(1, players.get(1).getTeamNumber());
        assertEquals(2, players.get(2).getTeamNumber());
        assertEquals(2, players.get(3).getTeamNumber());
    }

    @Test
    void listeFalscherLaengeWirdAbgelehnt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HumanMatch.applyTeams(sitze(4), List.of(1, 2, 2)),
                "drei Nummern fuer vier Sitze duerfen nicht stillschweigend verrutschen");
        assertTrue(e.getMessage().contains("je Sitz eine Teamnummer"), e.getMessage());
    }
}
