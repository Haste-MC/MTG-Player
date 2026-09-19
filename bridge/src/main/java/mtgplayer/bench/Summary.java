package mtgplayer.bench;

/** Auswertung ueber alle Spiele eines Bench-Laufs, siehe {@link BenchStats#summarize}. */
/** {@code crashes}: Spiele, die Forge mit einer Ausnahme abgebrochen hat (z. B. GameCopier "Couldn't map" in der
 *  Simulation) - zaehlen weder als Sieg noch als Unentschieden und nicht in Ø Zuege/Dauer. */
/** {@code simBroken}: Spiele, die durchliefen, in denen Forges Simulation aber Fehler geworfen hat (die Sim-KI
 *  spielt dann still nichts mehr) - wie Abstuerze aus allen Kennzahlen ausgenommen. */
public record Summary(int games, int winsA, int winsB, int draws, int drawsByTurnCap, int crashes, int simBroken, double winRateA,
                       double ciLow, double ciHigh, double avgTurns, double medianTurns, double avgMillis) {
}
