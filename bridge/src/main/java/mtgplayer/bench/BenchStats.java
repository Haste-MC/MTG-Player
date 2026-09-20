package mtgplayer.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reine Auswertung von Bench-Spielen (keine Forge-Abhaengigkeit): Siege/Unentschieden zaehlen,
 * Siegquote A unter den entschiedenen Spielen mit Wilson-Score-Intervall, Ø/Median der Zuege.
 */
public final class BenchStats {

    private BenchStats() { }

    /** Wilson-Score-Intervall fuer p = wins/n bei Konfidenzniveau z (1.96 fuer 95 %). n == 0 -&gt; [0, 1]. */
    public static double[] wilson(int wins, int n, double z) {
        if (n == 0) {
            return new double[] {0, 1};
        }
        double p = (double) wins / n;
        double denom = 1 + z * z / n;
        double center = (p + z * z / (2 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new double[] {Math.max(0, center - half), Math.min(1, center + half)};
    }

    public static Summary summarize(List<GameRecord> games) {
        int winsA = 0, winsB = 0, draws = 0, drawsByTurnCap = 0, crashes = 0, simBroken = 0, fewSpellsA = 0, fewSpellsB = 0;
        long turnSum = 0, millisSum = 0;
        List<Integer> turns = new ArrayList<>();
        for (GameRecord g : games) {
            if (g.crashed()) {
                crashes++;
                continue;
            }
            if (g.simBroken()) {
                simBroken++;
                continue;
            }
            turnSum += g.turns();
            millisSum += g.millis();
            turns.add(g.turns());
            if (g.fewSpells("A")) {
                fewSpellsA++;
            }
            if (g.fewSpells("B")) {
                fewSpellsB++;
            }
            if ("A".equals(g.winner())) {
                winsA++;
            } else if ("B".equals(g.winner())) {
                winsB++;
            } else {
                draws++;
                // Forge kann ein Spiel selbst mit GameEndReason.Draw beenden (gleichzeitiger Verlust,
                // Stack > 999, GameDrawEffect) - nur GameRecord.turnCapped() unterscheidet zuverlaessig
                // ein Unentschieden durch unseren Zugdeckel von einem Forge-eigenen.
                if (g.turnCapped()) {
                    drawsByTurnCap++;
                }
            }
        }
        int decided = winsA + winsB;
        double winRateA = decided == 0 ? 0 : (double) winsA / decided;
        double[] ci = wilson(winsA, decided, 1.96);
        Collections.sort(turns);
        int n = games.size();
        int played = n - crashes - simBroken;
        double avgTurns = played == 0 ? 0 : (double) turnSum / played;
        double avgMillis = played == 0 ? 0 : (double) millisSum / played;
        return new Summary(n, winsA, winsB, draws, drawsByTurnCap, crashes, simBroken, winRateA, ci[0], ci[1], avgTurns, median(turns), avgMillis, fewSpellsA, fewSpellsB);
    }

    private static double median(List<Integer> sortedTurns) {
        int n = sortedTurns.size();
        if (n == 0) {
            return 0;
        }
        int mid = n / 2;
        return n % 2 == 1 ? sortedTurns.get(mid) : (sortedTurns.get(mid - 1) + sortedTurns.get(mid)) / 2.0;
    }
}
