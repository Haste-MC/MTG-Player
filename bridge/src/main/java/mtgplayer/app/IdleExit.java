package mtgplayer.app;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Beendet die App, wenn das Fenster zu bleibt.
 *
 * <p>Das Fenster im App-Modus ist ein EIGENER Prozess (Edge/Chrome mit {@code --app=}, siehe
 * {@link AppMode#openWindow}); die Bridge merkt vom Schliessen nichts. Ohne diese Regel lief sie
 * unbemerkt weiter, hielt ihren Port und - weil der App-Ordner ihr Arbeitsverzeichnis ist - auch
 * diesen Ordner fest: Windows verweigerte dann das Loeschen ("Der Ordner ist in einem anderen
 * Programm geoeffnet"), und der einzige Ausweg war der Task-Manager. Wer das ZIP herunterlaedt, hat
 * kein Terminal mit Strg+C.
 *
 * <p>Das Schliessen des Fensters ist von einem blossen Neuladen der Seite nicht zu unterscheiden -
 * beides trennt die WebSocket-Verbindung. Deshalb die SCHONFRIST: erst wenn nach dem letzten
 * getrennten Klienten eine Weile keiner zurueckkommt, wird beendet. Kommt einer zurueck
 * ({@link #clientHere()}), ist der Abbruch abgesagt.</p>
 *
 * <p>Nur im App-Modus eingeschaltet (siehe {@code Main}): in der Entwicklung laeuft die Bridge im
 * Terminal weiter, auch wenn gerade kein Browser-Tab offen ist - dort beendet Strg+C sie.</p>
 */
public final class IdleExit {

    private final Duration grace;
    private final Runnable exit;
    private final ScheduledExecutorService timer;
    private ScheduledFuture<?> pending;

    public IdleExit(Duration grace, Runnable exit) {
        // Daemon: dieser eine Wartethread darf die JVM niemals am Leben halten - genau das Uebel,
        // gegen das die ganze Klasse gebaut ist.
        this(grace, exit, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idle-exit");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Fuer den Test: eigene Zeitplanung, damit die Schonfrist kurz sein kann. */
    IdleExit(Duration grace, Runnable exit, ScheduledExecutorService timer) {
        this.grace = grace;
        this.exit = exit;
        this.timer = timer;
    }

    /** Ein Klient ist da (Fenster offen oder Seite neu geladen): ein laufender Abbruch ist abgesagt. */
    public synchronized void clientHere() {
        cancelPending();
    }

    /** Der letzte Klient ist weg: nach der Schonfrist beenden, falls keiner zurueckkommt. */
    public synchronized void clientGone() {
        cancelPending();
        pending = timer.schedule(this::fire, grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void fire() {
        synchronized (this) {
            pending = null;
        }
        exit.run();
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}
