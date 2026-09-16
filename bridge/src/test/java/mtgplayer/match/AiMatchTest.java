package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

class AiMatchTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void vierKiSpielenCommanderZuEnde() {
        List<String> names = List.of("Abzan Armor [TDC] [2025]", "Adaptive Enchantment [C18] [2018]",
                "Ahoy Mateys [LCC] [2023]", "Animated Army [BLC] [2024]");
        List<Deck> decks = names.stream().map(Precons::load).toList();
        List<String> log = new ArrayList<>();

        AiMatch.Result r = AiMatch.play(decks, List.of("KI 1", "KI 2", "KI 3", "KI 4"), 60, log::add);

        assertNotNull(r);
        assertTrue(r.turns() > 1, "mindestens ein Zug gespielt, war " + r.turns());
        assertTrue(log.size() > 20, "Log erwartet, war " + log.size() + " Zeilen");
        assertTrue(r.turns() <= 60, "turn-cap nicht eingehalten: " + r.turns());
        System.out.println("Gewinner: " + r.winner() + " (" + r.reason() + ") nach " + r.turns() + " Zuegen");
    }
}
