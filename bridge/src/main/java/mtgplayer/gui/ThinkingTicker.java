package mtgplayer.gui;

import mtgplayer.forge.CrashLog;
import mtgplayer.protocol.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Macht das Rechnen der KI sichtbar und einen echten Haenger beweisbar.
 *
 * <p>Hintergrund: in einer Zuschauer-Partie mit vier Simulations-KIs kostet eine einzelne
 * Entscheidung gemessen 20–31 s. In dieser Zeit erreicht den Browser nichts - der Tisch wirkt
 * eingefroren, obwohl alles in Ordnung ist. Der Ticker prueft im Sekundentakt, wie lange die letzte
 * Aktivitaet her ist ({@link #touch()} aus {@code WebGuiGame.sendLog}/{@code pushState}) und schickt
 * ab {@value #SHOW_AFTER_S} s Stille je Sekunde eine {@link Messages.Thinking}-Nachricht. Sobald
 * wieder etwas passiert - oder das Spiel endet ({@link #close()}) - geht genau einmal
 * {@code seconds == 0} raus, womit der Browser die Anzeige loescht.</p>
 *
 * <p>Ab {@value #WATCHDOG_S} s Stille schreibt der Wachhund <b>einmal je Vorfall</b> ueber
 * {@link CrashLog#note} den Stacktrace des Spiel-Threads ins Bridge-Log (nur Log, keine Zeile im
 * Browser: eine lange KI-Rechnung ist normal und soll den Spieler nicht erschrecken). Er greift nicht
 * ein; er liefert nur das Beweismittel, mit dem sich beim naechsten Mal sofort unterscheiden laesst, ob
 * die KI rechnet (Stack in {@code GameSimulator}/{@code GameCopier}) oder wirklich etwas haengt (Stack
 * im {@code ChoiceBroker}/{@code CompletableFuture}). Erst nach Aktivitaet meldet er wieder.</p>
 *
 * <p><b>Warten auf den Menschen zaehlt nicht.</b> Ueberlegt ein Mensch zwei Minuten, steht der
 * Spiel-Thread im {@code ChoiceBroker} - genau die Signatur, die den <em>echten</em> Haenger ausmacht.
 * Ein Wachhund-Eintrag daraus waere kein Beweismittel mehr, sondern Rauschen, in dem das echte
 * Vorkommnis untergeht. Solange {@code waitingForHuman} wahr ist (offene Frage im Broker oder der Sitz
 * mit Prioritaet gehoert dem Menschen), schickt {@link #tick()} deshalb nichts, laesst den Wachhund
 * stumm, loescht eine laufende Anzeige genau einmal und setzt die Stille-Uhr weiter - sonst stuende in
 * der Sekunde nach der Antwort "denkt seit 300 s" und der Wachhund schluege sofort zu.</p>
 *
 * <p><b>Threads.</b> {@link #touch()} laeuft auf dem Spiel-Thread (Log-Observer) und darf deshalb
 * nichts tun ausser ein Feld zu schreiben; die Arbeit macht der naechste {@link #tick()} auf dem
 * Taktgeber-Thread. Ob seither etwas passiert ist, erkennt der Takt am Zeitstempel selbst
 * ({@code lastActivityNanos} gegen das gemerkte {@code lastSeenActivityNanos}) - ein eigener Zaehler
 * waere ein nicht-atomares {@code ++} aus zwei Threads. Alles, was nach draussen geht
 * ({@code out.accept}, der Wachhund-Eintrag), wird unter dem {@code state}-Monitor nur gesammelt und
 * erst danach verschickt: {@link #close()} braucht denselben Monitor vom UI-Thread und darf nicht
 * hinter einer blockierenden Verbindung warten. Die Zeitquelle ist ein {@link LongSupplier} (im Betrieb
 * {@code System::nanoTime}), damit der Test takten kann, ohne zu warten.</p>
 */
public final class ThinkingTicker implements AutoCloseable {

    /** Ab so viel Stille faengt die Anzeige an - kurze Pausen sollen nicht flackern. */
    public static final int SHOW_AFTER_S = 3;

    /** Ab so viel Stille schreibt der Wachhund einmal ins Bridge-Log. */
    public static final int WATCHDOG_S = 120;

    private static final long SECOND_NANOS = 1_000_000_000L;

    private final Consumer<Object> out;
    private final LongSupplier nanos;
    private final Supplier<Integer> priorityPlayer;
    private final Supplier<String> phaseInfo;
    private final Supplier<Thread> gameThread;
    private final BooleanSupplier waitingForHuman;

    /** Scharf ab {@link #arm()}, aus ab {@link #close()} - ohne laufendes Spiel passiert nichts. */
    private volatile boolean armed;
    private volatile boolean closed;
    private Thread worker;

    /** Zeitpunkt der letzten Aktivitaet, geschrieben aus {@link #touch()} (Spiel-Thread) und aus dem Takt. */
    private volatile long lastActivityNanos;

    private final Object state = new Object();
    private long lastSeenActivityNanos;          // guarded by state
    private boolean showing;                     // guarded by state
    private boolean watchdogReported;            // guarded by state

    /**
     * @param out             Ausgang zum Browser (im Betrieb {@code WebGuiGame}s Transport)
     * @param nanos           Zeitquelle, im Betrieb {@code System::nanoTime}
     * @param priorityPlayer  Sitz mit Prioritaet aus dem zuletzt gebauten Snapshot, {@code null} wenn unbekannt
     * @param phaseInfo       Kontextzeile fuer den Wachhund ("Prioritaet: …, Phase: …, Zug …")
     * @param gameThread      Forges Spiel-Thread, {@code null} solange er nicht bekannt ist
     * @param waitingForHuman wahr, solange die Bridge auf eine Eingabe des Menschen wartet - dann ruht alles
     */
    public ThinkingTicker(Consumer<Object> out, LongSupplier nanos, Supplier<Integer> priorityPlayer,
                          Supplier<String> phaseInfo, Supplier<Thread> gameThread,
                          BooleanSupplier waitingForHuman) {
        this.out = out;
        this.nanos = nanos;
        this.priorityPlayer = priorityPlayer;
        this.phaseInfo = phaseInfo;
        this.gameThread = gameThread;
        this.waitingForHuman = waitingForHuman;
        this.lastActivityNanos = nanos.getAsLong();
        this.lastSeenActivityNanos = this.lastActivityNanos;
    }

    /**
     * Scharf schalten und den Taktgeber (Daemon-Thread, 1 s) anwerfen. Mehrfaches Aufrufen ist
     * wirkungslos, nach {@link #close()} laesst sich der Ticker nicht wieder starten - jede Partie
     * bekommt einen eigenen.
     */
    public synchronized void start() {
        if (worker != null || closed) {
            return;
        }
        arm();
        worker = new Thread(this::loop, "thinking-ticker");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Nur scharf schalten, ohne Taktgeber-Thread. {@link #start()} macht beides; der Test taktet
     * selbst ueber {@link #tick()} und will keinen zweiten Taktgeber daneben.
     */
    void arm() {
        if (closed) {
            return;
        }
        long now = nanos.getAsLong();
        lastActivityNanos = now;
        synchronized (state) {
            lastSeenActivityNanos = now;
        }
        armed = true;
    }

    /**
     * Aktivitaet gesehen (Log-Zeile, Zustands-Push). Laeuft auf dem Spiel-Thread und tut deshalb
     * absichtlich nichts weiter: der naechste {@link #tick()} sieht den neuen Zeitstempel, beendet eine
     * laufende Anzeige und setzt den Wachhund zurueck.
     */
    public void touch() {
        lastActivityNanos = nanos.getAsLong();
    }

    /**
     * Ein Takt. Im Betrieb ruft ihn der Taktgeber-Thread je Sekunde, im Test der Test selbst.
     * Ohne laufendes Spiel ({@link #arm()}/{@link #start()} nicht gerufen oder schon
     * {@link #close()}) passiert nichts.
     */
    public void tick() {
        if (!armed || closed) {
            return;
        }
        // Alles Fremde (Uhr, Snapshot, Broker) VOR dem Monitor holen - drinnen wird nur gerechnet.
        long now = nanos.getAsLong();
        boolean human = waitingForHuman.getAsBoolean();
        Integer priority = human ? null : priorityPlayer.get();

        List<Object> send = new ArrayList<>(2);
        boolean watchdog = false;
        long silence;
        synchronized (state) {
            if (closed) {
                // close() kann zwischen der Pruefung oben und hier durchgelaufen sein; sonst schoebe
                // sich noch eine Denk-Anzeige HINTER die Abschlussmeldung.
                return;
            }
            long activity = lastActivityNanos;
            if (activity != lastSeenActivityNanos) {   // seit dem letzten Takt ist etwas passiert
                lastSeenActivityNanos = activity;
                watchdogReported = false;
                clearShowing(send);
            }
            silence = (now - activity) / SECOND_NANOS;
            if (human) {
                // Die Bridge wartet auf den Menschen: nichts anzeigen, nichts melden - und die Uhr
                // mitziehen, damit nach seiner Antwort nicht die ganze Bedenkzeit als Stille dasteht.
                lastActivityNanos = now;
                lastSeenActivityNanos = now;
                watchdogReported = false;
                clearShowing(send);
            } else {
                if (silence >= SHOW_AFTER_S) {
                    send.add(new Messages.Thinking(priority, (int) silence));
                    showing = true;
                }
                if (silence >= WATCHDOG_S && !watchdogReported) {
                    watchdogReported = true;
                    watchdog = true;
                }
            }
        }
        for (Object m : send) {
            out.accept(m);
        }
        if (watchdog) {
            CrashLog.note("Wachhund", silence + " s ohne Fortschritt – " + info() + "\n" + stack());
        }
    }

    /**
     * Taktgeber beenden und eine laufende Anzeige abschliessen. Danach ist der Ticker endgueltig
     * aus - {@code WebGuiGame} legt fuer die naechste Partie einen neuen an.
     */
    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            armed = false;
            t = worker;
            worker = null;
        }
        if (t != null) {
            t.interrupt();                       // der Taktgeber haengt fast immer im sleep(1 s)
        }
        List<Object> send = new ArrayList<>(1);
        synchronized (state) {
            clearShowing(send);
        }
        for (Object m : send) {
            out.accept(m);
        }
    }

    // ---------------------------------------------------------------- innen

    /** Einmalig {@code seconds: 0} - der Browser loescht damit die Anzeige. */
    private void clearShowing(List<Object> send) {
        if (showing) {
            showing = false;
            send.add(new Messages.Thinking(null, 0));
        }
    }

    private void loop() {
        while (!closed) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                tick();
            } catch (RuntimeException e) {
                // Eine kaputte Verbindung oder ein halb abgebauter Snapshot darf den Taktgeber nicht
                // mitreissen - sonst faellt die Anzeige fuer den Rest der Partie aus.
                System.err.println("[thinking] Takt fehlgeschlagen: " + e);
            }
        }
    }

    private String info() {
        String s = phaseInfo.get();
        return s == null || s.isBlank() ? "(Zustand unbekannt)" : s;
    }

    /** Stacktrace des Spiel-Threads - das eigentliche Beweismittel; ohne ihn wenigstens der Vermerk. */
    private String stack() {
        Thread t = gameThread.get();
        if (t == null) {
            return "(Spiel-Thread unbekannt)";
        }
        StringBuilder sb = new StringBuilder("Spiel-Thread \"").append(t.getName()).append("\" (")
                .append(t.getState()).append(')');
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\n\tat ").append(e);
        }
        return sb.toString();
    }
}
