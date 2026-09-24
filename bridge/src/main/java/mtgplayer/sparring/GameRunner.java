package mtgplayer.sparring;

import mtgplayer.stats.MatchRecord;

/**
 * Spielt genau eine Sparring-Partie. In Betrieb ist das {@link SubprocessGameRunner} (ein eigener
 * JVM-Kindprozess je Partie, damit ein Forge-Absturz Kevins laufende Bridge nicht mitnimmt), in den
 * Tests eine Attrappe - {@link SparringRun} kennt nur diese Naht.
 */
public interface GameRunner {

    /**
     * @param seed Seed fuer {@code MyRandom} im Kindprozess; die Sitzreihenfolge haengt daran
     * @return der Datensatz der Partie (Quelle {@code "sparring"})
     * @throws RuntimeException Partie fehlgeschlagen (Absturz, Zeitlimit, kein Ergebnis) - der Lauf
     *                          zaehlt sie, vermerkt den Grund und macht weiter
     */
    MatchRecord play(SparringArgs args, String opponent, long seed);
}
