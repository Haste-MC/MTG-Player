package mtgplayer.ai;

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

/** Die Voll-Simulation ({@code sim}) muss die KI-Bedenkzeit je Entscheidung einhalten (Forge-Fork,
 *  {@code SimulationController}-Deadline). Ohne das Budget hing der Abzan-Spiegel im Bench > 30 min in
 *  einer einzigen Entscheidung. */
class SimBudgetTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void simEntscheidungenHaltenDasBudgetEin() {
        // Abzan-Spiegel: das Board, das im Bench > 30 min in einer Entscheidung hing.
        List<Deck> decks = List.of(Precons.load("Abzan Armor [TDC] [2025]"), Precons.load("Abzan Armor [TDC] [2025]"));
        List<AiConfig> cfg = List.of(AiConfig.parse("sim"), AiConfig.DEFAULT);
        MyRandom.setRandom(new Random(1));
        long t0 = System.nanoTime();
        AiMatch.Result r = AiMatch.play(decks, List.of("A", "B"), cfg, 3, 26, s -> { });
        double seconds = (System.nanoTime() - t0) / 1e9;
        // Wanduhr-Smoke-Test, keine Messung je Entscheidung: 26 Spielerzuege, davon 13 fuer die Sim-KI mit
        // je ~2 Entscheidungen (Main 1/2) a max. 3 s Budget, plus Kampf und Standard-KI - grosszuegige
        // Gesamtschranke, die ohne Budget (>30 min) sicher reisst.
        assertTrue(seconds < 240, "Sim-Spiel brauchte " + seconds + " s fuer " + r.turns() + " Zuege");
    }
}
