package mtgplayer.forge;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Abbrueche des Spiel-Threads sichtbar machen: Forge meldet sie ueber {@code showBugReportDialog} oder als
 * uncaught exception, die Bridge-JVM lief bisher einfach weiter und der Tisch im Browser wirkte "eingefroren"
 * (der Spiel-Thread ist tot, kein Prompt kommt mehr). Jeder Abbruch landet jetzt mit Stacktrace in
 * {@code ~/.mtg-player/logs/bridge.log} und - wenn ein Listener registriert ist (Bridge) - als Fehlermeldung
 * im Browser-Log.
 *
 * <p>{@link #warn} schreibt dieselbe Zeile ohne Absturz-Semantik (keine Crash-Listener, kein
 * "Spiel abgebrochen" im Browser) - fuer Pannen abseits des laufenden Spiels.</p>
 */
public final class CrashLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Consumer<String> listener;
    private static final List<Runnable> crashListeners = new CopyOnWriteArrayList<>();
    private static volatile Path fileOverride;

    private CrashLog() { }

    public static void setListener(Consumer<String> l) {
        listener = l;
    }

    /**
     * Zweiter Empfaengerkanal neben {@link #setListener}: beliebig viele Interessenten, die einen
     * Abbruch nur als Tatsache brauchen (kein Text). {@link mtgplayer.stats.MatchRecorder} markiert
     * damit die laufende Partie als "Absturz" und nimmt sie aus der Wertung. Bewusst eine Liste statt
     * eines einzelnen Listeners: {@code setListener} gehoert der Bridge (Fehlerzeile im Browser), und
     * jede laufende Partie meldet sich zusaetzlich selbst an.
     *
     * <p>{@code CopyOnWriteArrayList}, weil {@link #report} vom Spiel-Thread (oder einem beliebigen
     * Thread mit einer uncaught exception) laeuft, waehrend sich ein Recorder gerade an- oder
     * abmeldet - so braucht {@link #report} keine Sperre und eine Abmeldung waehrend der Zustellung
     * ist unproblematisch.</p>
     */
    public static void addCrashListener(Runnable l) {
        crashListeners.add(l);
    }

    /** Gegenstueck zu {@link #addCrashListener}; eine unbekannte Anmeldung zu entfernen ist erlaubt. */
    public static void removeCrashListener(Runnable l) {
        crashListeners.remove(l);
    }

    /** Zieldatei umlenken (Tests); {@code null} = wieder {@code ~/.mtg-player/logs/bridge.log}. */
    public static void setFile(Path f) {
        fileOverride = f;
    }

    public static Path file() {
        Path f = fileOverride;
        return f != null ? f : ForgeBoot.dataDir().resolve("logs").resolve("bridge.log");
    }

    /** @param title kurze Ueberschrift (Thread-Name oder Forge-Titel), @param text Forge-Text oder null */
    public static synchronized void report(String title, String text, Throwable e) {
        write(title, text, e);
        for (Runnable r : crashListeners) {
            try {
                r.run();
            } catch (RuntimeException ex) {
                System.err.println("[crashlog] Listener hat geworfen: " + ex);
            }
        }
        Consumer<String> l = listener;
        if (l != null) {
            String first = e == null ? (text == null ? title : text.strip().lines().findFirst().orElse(title))
                    : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            l.accept("Spiel abgebrochen (" + title + "): " + first + " – Details in " + file());
        }
    }

    /**
     * Dieselbe Zeile in {@code bridge.log} wie {@link #report}, aber OHNE Absturz-Semantik: die
     * {@link #addCrashListener}-Empfaenger werden nicht benachrichtigt und im Browser steht kein
     * "Spiel abgebrochen", sondern eine schlichte Fehlerzeile.
     *
     * <p>Fuer Pannen, die nichts mit dem laufenden Spiel zu tun haben - z. B. eine kaputte
     * {@code matches.json} ({@link mtgplayer.stats.MatchStore}). Ueber {@link #report} gemeldet, haette
     * sie die gerade laufende Partie als "Absturz" aus der Wertung genommen und dem Browser ein Spielende
     * vorgegaukelt.</p>
     */
    public static synchronized void warn(String title, String text) {
        write(title, text, null);
        Consumer<String> l = listener;
        if (l != null) {
            l.accept(title + ": " + (text == null || text.isBlank() ? "(ohne Text)" : text.strip().lines().findFirst().orElse(title))
                    + " – Details in " + file());
        }
    }

    /** Zeitstempel + Titel + Text (+ Stacktrace) nach stderr und in {@link #file()}. */
    private static void write(String title, String text, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(STAMP.format(LocalDateTime.now())).append(' ').append(title);
        if (text != null && !text.isBlank()) {
            sb.append(": ").append(text.strip());
        }
        sb.append('\n');
        if (e != null) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        sb.append('\n');
        String entry = sb.toString();
        System.err.print(entry);
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException io) {
            System.err.println("[crashlog] konnte nicht schreiben: " + io);
        }
    }
}
