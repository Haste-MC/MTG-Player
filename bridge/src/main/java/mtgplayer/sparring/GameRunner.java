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

    /**
     * Beendet eine gerade laufende Partie, so dass {@link #play} zeitnah scheitert - beim
     * {@link SubprocessGameRunner} heisst das: den Kindprozess abschiessen. Darf jederzeit und aus
     * einem fremden Thread kommen (der Lauf ruft es aus {@link SparringRun#cancel()}, das seinerseits
     * am WebSocket-Thread haengt) und darf nicht blockieren.
     *
     * <p>Ohne diese Naht wuerde "Abbrechen" erst zwischen zwei Partien wirken - eine 1vs1-Partie
     * dauert je nach KI Minuten, ein Knopf, der so lange nichts tut, ist keiner (Spec Abschnitt 3:
     * "laufendes Kind wird beendet").</p>
     *
     * <p>Voreinstellung: nichts tun - fuer Attrappen, die ohnehin sofort zurueckkehren.</p>
     */
    default void cancel() { }
}
