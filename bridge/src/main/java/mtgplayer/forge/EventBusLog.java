package mtgplayer.forge;

import com.google.common.eventbus.EventBus;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Das stillste Loch der Bridge zumachen: Ausnahmen aus Ereignis-Abonnenten.
 *
 * <p>Forge verteilt alles, was in einer Partie passiert, ueber einen {@link EventBus} von Guava
 * ({@code Game.fireEvent}). Wirft ein Abonnent, faengt Guava das ab - der Bus soll an einem kaputten
 * Empfaenger nicht zerbrechen - und schreibt es ueber {@code java.util.logging} weg. Bei uns landete
 * das in der Voreinstellung auf {@code stderr}, also im Terminal: nicht in
 * {@code ~/.mtg-player/logs/bridge.log} und nicht im Browser. Wer das Fenster schliesst, hat das
 * Beweismittel weggeworfen.
 *
 * <p>Das ist keine theoretische Luecke. An diesem Bus haengt Forges {@code FControlGameEventHandler},
 * der jede Zustandsaenderung an die Oberflaeche weitergibt. Verschluckt er eine Ausnahme, hoert der
 * Tisch im Browser einfach auf, sich zu bewegen, und im Log steht nichts - genau das Bild der zwei
 * Vorfaelle vom 2026-10-06 (siehe {@link ThreadDump}).
 *
 * <p><b>{@link CrashLog#warn}, nicht {@link CrashLog#report}.</b> Der Spieler soll die Zeile sehen -
 * ein Abonnent, der aufgibt, hat eine Zustandsaenderung verloren, die niemand nachliefert. Aber die
 * Partie ist deswegen nicht abgebrochen: der Bus laeuft weiter, die uebrigen Abonnenten haben das
 * Ereignis bekommen, und das Spiel spielt weiter. {@code report} wuerde dem Browser "Spiel
 * abgebrochen" melden und die Partie aus der Wertung nehmen - beides waere hier schlicht nicht wahr.
 * Fuer einen Thread, der wirklich stirbt, ist {@code report} zustaendig (siehe
 * {@code WebGuiBase.invokeInEdtLater} und der Standard-Handler in {@link ForgeBoot}).
 */
public final class EventBusLog {

    /**
     * Starke Referenz auf den Logger: {@code java.util.logging} haelt seine Logger nur schwach, ein
     * nur ueber {@code Logger.getLogger(...)} erreichbarer Logger kann samt unserem Handler
     * eingesammelt werden - und dann waere die Luecke nach einer Weile wieder offen.
     */
    private static Logger busLogger;

    private EventBusLog() { }

    /** Mehrfach aufrufbar; nur der erste Aufruf haengt den Handler an. */
    public static synchronized void install() {
        if (busLogger != null) {
            return;
        }
        Logger logger = Logger.getLogger(EventBus.class.getName());
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                // Guava meldet den Namen des Abonnenten und das Ereignis im Text und die Ausnahme
                // als "thrown"; ohne Ausnahme ist es kein Vorfall (der Bus loggt z. B. auch
                // Ereignisse ohne Empfaenger, und das ist voellig normal).
                if (record == null || record.getThrown() == null
                        || record.getLevel().intValue() < Level.WARNING.intValue()) {
                    return;
                }
                CrashLog.warn("eventbus", record.getMessage() + "\n" + stack(record.getThrown()));
            }

            @Override public void flush() { }
            @Override public void close() { }

            /** {@link CrashLog#warn} nimmt keine Ausnahme - den Stacktrace also selbst anhaengen. */
            private String stack(Throwable t) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                return sw.toString();
            }
        });
        // Nicht weiter nach oben: sonst schreibt zusaetzlich der Standard-Handler von
        // java.util.logging dieselbe Ausnahme nach stderr, und sie stuende im Terminal doppelt
        // (CrashLog gibt jeden Eintrag selbst dort aus). Verloren geht dabei nichts - was ohne
        // Ausnahme kommt, zeigt java.util.logging in der Voreinstellung ohnehin nicht an.
        logger.setUseParentHandlers(false);
        busLogger = logger;
    }
}
