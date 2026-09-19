package mtgplayer.bench;

/** Auswertung ueber alle Spiele eines Bench-Laufs, siehe {@link BenchStats#summarize}. */
public record Summary(int games, int winsA, int winsB, int draws, int drawsByTurnCap, double winRateA,
                       double ciLow, double ciHigh, double avgTurns, double medianTurns, double avgMillis) {
}
