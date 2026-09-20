package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import forge.deck.Deck;
import forge.util.MyRandom;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.match.AiMatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Der {@code GameCopier} der Voll-Simulation ({@code sim}) darf an Monarch-Effektkarte und an der
 *  {@code <Nothing>}-Platzhalterkarte aus {@code Combat.removeFromCombat} (angegriffener Planeswalker
 *  stirbt) nicht mehr mit {@code Couldn't map ...} abstuerzen (Forge-Fork). Seeds und Sitzreihenfolge
 *  stammen aus dem Bench {@code docs/bench/2026-09-20-sim-vs-standard.md}. */
class SimCopierTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Ahoy-Mateys-Spiegel (Monarch-Karten), Sim auf A, Seed 5: Bench-Absturz
     *  {@code Couldn't map The Monarch} nach ca. 12 Zuegen. */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void monarchEffektkarteUeberlebtDieKopie() {
        Deck ahoy = Precons.load("Ahoy Mateys [LCC] [2023]");
        List<AiConfig> cfg = List.of(AiConfig.parse("sim"), AiConfig.DEFAULT);
        MyRandom.setRandom(new Random(5));
        assertDoesNotThrow(() -> AiMatch.play(List.of(ahoy, ahoy), List.of("A", "B"), cfg, 3, 30, s -> { }));
    }

    /** Abzan Armor (sim, A) gegen Adaptive Enchantment (std, B, Planeswalker-Commander Estrid), Seed 4:
     *  Bench-Absturz {@code Couldn't map <Nothing>}, Estrid stirbt im Kampf um Zug 9. Sitzreihenfolge wie
     *  im Bench fuer Spielindex 3: Liste [B, A]. */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void nothingPlatzhalterUeberlebtDieKopie() {
        List<Deck> decks = List.of(Precons.load("Adaptive Enchantment [C18] [2018]"), Precons.load("Abzan Armor [TDC] [2025]"));
        List<AiConfig> cfg = List.of(AiConfig.DEFAULT, AiConfig.parse("sim"));
        MyRandom.setRandom(new Random(4));
        assertDoesNotThrow(() -> AiMatch.play(decks, List.of("B", "A"), cfg, 3, 30, s -> { }));
    }
}
