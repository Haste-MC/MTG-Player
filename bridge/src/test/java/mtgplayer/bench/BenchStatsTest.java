package mtgplayer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchStatsTest {
    private static GameRecord g(int i, String w, int turns) {
        return new GameRecord(i, 1 + i, i % 2 == 0 ? "A" : "B", w, w == null ? "Draw" : "AllOpponentsLost", turns, 1000);
    }

    @Test void wilsonBekannteWerte() {
        double[] ci = BenchStats.wilson(30, 40, 1.96);      // p = 0.75 → ca. [0.597, 0.859]
        assertEquals(0.597, ci[0], 0.005); assertEquals(0.859, ci[1], 0.005);
        double[] none = BenchStats.wilson(0, 0, 1.96);
        assertEquals(0, none[0]); assertEquals(1, none[1]);
    }
    @Test void summaryZaehltSiegeUnentschiedenUndZuege() {
        var s = BenchStats.summarize(List.of(g(0, "A", 20), g(1, "B", 30), g(2, "A", 40), g(3, null, 200)));
        assertEquals(4, s.games()); assertEquals(2, s.winsA()); assertEquals(1, s.winsB()); assertEquals(1, s.draws());
        assertEquals(2.0 / 3, s.winRateA(), 1e-9);          // nur entschiedene Spiele
        assertEquals(72.5, s.avgTurns(), 1e-9); assertEquals(35, s.medianTurns(), 1e-9);
    }
}
