package mtgplayer.bench;

/**
 * Fuehrt genau ein Bench-Spiel aus. {@link InProcessRunner} spielt direkt im aufrufenden Thread (fuer
 * Tests und {@code --in-process}), {@link SubprocessRunner} startet dafuer einen frischen JVM-Kindprozess
 * (Standard fuer {@code --bench}): ein Forge-eigener Absturz waehrend der Simulation vergiftet globalen
 * Zustand so, dass Folgespiele in derselben JVM reihenweise abstuerzen - siehe
 * {@code .superpowers/sdd/bench-subprocess-brief.md}.
 */
public interface GameRunner {

    GameRecord play(BenchArgs args, int i);

    /** Beendet einen evtl. noch laufenden Kindprozess (z. B. bei Ctrl-C waehrend eines Spiels).
     *  {@link InProcessRunner} braucht das nicht, {@link SubprocessRunner} ueberschreibt es. */
    default void shutdown() { }
}
