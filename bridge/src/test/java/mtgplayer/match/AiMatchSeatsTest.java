package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;
import mtgplayer.ai.AiConfig;
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

    @Test
    void deckNamenFolgenIhremSitz() {
        var deckNames = new HashMap<RegisteredPlayer, String>();
        List<RegisteredPlayer> rp = AiMatch.registered(decks(4), List.of("A1", "A2", "B1", "B2"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), deckNames);

        assertEquals("D2", deckNames.get(rp.get(1)), "Sitz 1 ist der alte Index 2 - mit dessen Deck");
    }
}
