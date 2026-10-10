package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;
import mtgplayer.ai.AiConfig;
import mtgplayer.ai.AiLobbyPlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Die Sitzliste der headless Partie: mit Teams abwechselnd sortiert, und die Teamnummer wandert mit dem
 *  Sitz mit - sonst traegt hinterher der falsche Sitz das falsche Team. */
class AiMatchSeatsTest {

    private static List<Deck> decks(int n) {
        List<Deck> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Deck("D" + i));
        }
        return out;
    }

    @Test
    void ohneTeamsBleibtDieReihenfolge() {
        List<RegisteredPlayer> rp = AiMatch.registered(decks(2), List.of("A", "B"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT), null, new HashMap<>());

        assertEquals(List.of("A", "B"), rp.stream().map(p -> p.getPlayer().getName()).toList());
        assertEquals(List.of(-1, -1), rp.stream().map(RegisteredPlayer::getTeamNumber).toList());
    }

    @Test
    void mitTeamsWechselnSichDieSitzeAb() {
        List<RegisteredPlayer> rp = AiMatch.registered(decks(4), List.of("A1", "A2", "B1", "B2"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), new HashMap<>());

        assertEquals(List.of("A1", "B1", "A2", "B2"), rp.stream().map(p -> p.getPlayer().getName()).toList(),
                "abwechselnd, Sitz 0 bleibt vorn");
        assertEquals(List.of(1, 2, 1, 2), rp.stream().map(RegisteredPlayer::getTeamNumber).toList(),
                "die Teamnummer wandert mit dem Sitz mit");
    }

    /** Vier verschiedene KI-Einstellungen in Lobby-Reihenfolge. Direkt gebaut statt per
     *  {@code AiConfig.parse}: das braucht ForgeBoot und damit {@code ~/.mtg-player}, der Sitztest nicht. */
    private static final List<AiConfig> VIER_CONFIGS = List.of(
            new AiConfig(AiConfig.Mode.SIM, "Default"),
            new AiConfig(AiConfig.Mode.STANDARD, "Default"),
            new AiConfig(AiConfig.Mode.HYBRID, "Default"),
            new AiConfig(AiConfig.Mode.SIM, "Reckless"));

    @Test
    void deckNamenFolgenIhremSitz() {
        var deckNames = new HashMap<RegisteredPlayer, String>();
        List<RegisteredPlayer> rp = AiMatch.registered(decks(4), List.of("A1", "A2", "B1", "B2"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), deckNames);

        assertEquals("D2", deckNames.get(rp.get(1)), "Sitz 1 ist der alte Index 2 - mit dessen Deck");
    }

    @Test
    void jederSitzTraegtSeineEigeneKiEinstellungUndSeinDeck() {
        // Lobby 0..3 = A1, A2, B1, B2 mit je eigener Config und eigenem Deck; gespielt wird 0, 2, 1, 3.
        // Mit lauter AiConfig.DEFAULT bliebe gruen, wenn nur die Namen umsortiert werden und die
        // Config-Liste nicht (configs.get(pos) statt configs.get(i)) - hier faellt das auf.
        var deckNames = new HashMap<RegisteredPlayer, String>();
        List<RegisteredPlayer> rp = AiMatch.registered(decks(4), List.of("A1", "A2", "B1", "B2"),
                VIER_CONFIGS, List.of(1, 1, 2, 2), deckNames);

        int[] lobbyIndexJeSitz = {0, 2, 1, 3};
        List<String> lobbyNamen = List.of("A1", "A2", "B1", "B2");
        List<Integer> lobbyTeams = List.of(1, 1, 2, 2);
        for (int sitz = 0; sitz < rp.size(); sitz++) {
            int lobby = lobbyIndexJeSitz[sitz];
            RegisteredPlayer p = rp.get(sitz);
            String wo = "Sitz " + sitz + " (Lobby " + lobby + ")";

            assertEquals(lobbyNamen.get(lobby), p.getPlayer().getName(), wo + ": Name");
            assertEquals(VIER_CONFIGS.get(lobby), ((AiLobbyPlayer) p.getPlayer()).config(), wo + ": KI-Einstellung");
            assertEquals("D" + lobby, p.getDeck().getName(), wo + ": Deck des RegisteredPlayer");
            assertEquals("D" + lobby, deckNames.get(p), wo + ": Deckname fuer den Recorder");
            assertEquals(lobbyTeams.get(lobby), p.getTeamNumber(), wo + ": Teamnummer");
        }
    }

    @Test
    void playLehntEineTeamlisteFalscherLaengeAb() {
        // Die Pruefung sitzt vor allem Forge-Zeug und kostet keine Partie. Zu kurz wie zu lang: eine
        // Nummer zu wenig liesse einen Sitz ohne Team, eine zu viel verschoebe die Zuordnung.
        List<String> namen = List.of("A1", "A2", "B1", "B2");
        List<AiConfig> configs = VIER_CONFIGS;
        for (List<Integer> falsch : List.of(List.of(1, 1, 2), List.of(1, 1, 2, 2, 2))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> AiMatch.play(decks(4), namen, configs, falsch, 3, 10, line -> { }),
                    "Teamliste mit " + falsch.size() + " Eintraegen fuer 4 Decks");
            assertTrue(e.getMessage().contains("Teams brauchen gleich viele Eintraege"), e.getMessage());
        }
    }
}
