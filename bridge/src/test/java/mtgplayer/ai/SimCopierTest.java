package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 *  stammen aus dem Bench {@code docs/bench/2026-09-20-sim-vs-standard.md}.
 *
 *  <p>Jeder Fall prueft zusaetzlich, dass die Partie mindestens bis zu dem Zug kommt, an dem der
 *  Bench-Absturz dokumentiert ist ({@code r.turns() >= N}) - sonst koennte ein Test "bestehen", weil die
 *  Partie durch einen anderen, frueheren Fehler abweicht und die eigentliche Stelle nie erreicht. Die
 *  Reproduktion haengt am Zeitbudget (Wanduhr, siehe {@code SimulationController}-Deadline) und an der
 *  Maschine, ist also nicht hart garantiert; eine deterministische {@code GameCopier}-Testvariante (direktes
 *  {@code makeCopy()} auf einem praeparierten Spiel ohne Zeitbudget) ist das Ziel fuer spaeter. */
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
        AiMatch.Result r = assertDoesNotThrow(
                () -> AiMatch.play(List.of(ahoy, ahoy), List.of("A", "B"), cfg, 3, 30, s -> { }));
        assertTrue(r.turns() >= 12, "Partie endete schon Zug " + r.turns() + ", vor dem dokumentierten Absturz-Zug 12");
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
        AiMatch.Result r = assertDoesNotThrow(() -> AiMatch.play(decks, List.of("B", "A"), cfg, 3, 30, s -> { }));
        assertTrue(r.turns() >= 9, "Partie endete schon Zug " + r.turns() + ", vor dem dokumentierten Absturz-Zug 9");
    }

    /** Ahoy-Spiegel, Sim auf A, Seed 30, Sitzreihenfolge [B, A] (Bench-Spielindex 29): B ruestet mit
     *  Gemcutter Buccaneer Treasure-Token als Equipment an, opfert sie spaeter fuer Mana - Bench-Absturz
     *  {@code Couldn't map Treasure Token} zu Beginn von As Zug 16. */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void geopferteAusruestungUeberlebtDieKopie() {
        Deck ahoy = Precons.load("Ahoy Mateys [LCC] [2023]");
        List<AiConfig> cfg = List.of(AiConfig.DEFAULT, AiConfig.parse("sim"));
        MyRandom.setRandom(new Random(30));
        // aiTimeout 10 wie im Bench: mit 3 s Budget verlaeuft die Partie anders und erreicht die Stellung nicht.
        AiMatch.Result r = assertDoesNotThrow(
                () -> AiMatch.play(List.of(ahoy, ahoy), List.of("B", "A"), cfg, 10, 30, s -> { }));
        assertTrue(r.turns() >= 16, "Partie endete schon Zug " + r.turns() + ", vor dem dokumentierten Absturz-Zug 16");
    }
}
