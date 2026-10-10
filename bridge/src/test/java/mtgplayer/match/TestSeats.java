package mtgplayer.match;

import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;
import mtgplayer.ai.AiConfig;

import java.util.HashMap;
import java.util.List;

/** Reicht die paketsichtbare Sitzliste der headless Partie ({@link AiMatch#registered}) an Tests in
 *  anderen Paketen durch - ohne die Produktion nur dafuer zu oeffnen. */
public final class TestSeats {

    private TestSeats() { }

    public static List<RegisteredPlayer> headless(List<Deck> decks, List<String> names, List<AiConfig> configs,
                                                  List<Integer> teams) {
        return AiMatch.registered(decks, names, configs, teams, new HashMap<>());
    }
}
