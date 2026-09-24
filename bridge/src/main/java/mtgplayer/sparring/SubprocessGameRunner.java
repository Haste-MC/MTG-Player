package mtgplayer.sparring;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.proc.ChildJvm;
import mtgplayer.protocol.Json;
import mtgplayer.stats.MatchRecord;

/**
 * Spielt jede Sparring-Partie in einem eigenen JVM-Kindprozess ({@code Main --sparring-one <json>},
 * Ergebniszeile {@code SPARRING_RESULT <json MatchRecord>}) - Spec Abschnitt 3: "ein Forge-Absturz darf
 * Kevins laufende Bridge nicht mitnehmen, er spielt ja nebenher". Die Mechanik des Kindprozesses steckt
 * in {@link ChildJvm}, dieselbe Grundlage wie beim Bench.
 *
 * <p>Kein Ergebnis, Exit ≠ 0 oder Ablauf des Zeitlimits werfen - {@link SparringRun} zaehlt die Partie
 * dann als Fehler und macht weiter. {@link #cancel()} schiesst das laufende Kind ab, damit "Abbrechen"
 * sofort wirkt und nicht erst nach Minuten; {@code play} scheitert dann wie bei jedem anderen Absturz.</p>
 *
 * <p>stderr und stdout des Kindes liegen unter {@code <dataDir>/sparring/<name>.log} bzw.
 * {@code <name>.out.log}, mit {@code name} aus {@link #logBaseName} - Zeitpunkt, laufende Nummer und
 * Gegner, damit ein zweiter Lauf (oder ein Bridge-Neustart) die Protokolle des ersten nicht
 * ueberschreibt.</p>
 */
public final class SubprocessGameRunner implements GameRunner {

    static final String RESULT_PREFIX = "SPARRING_RESULT ";

    /** Wie beim Bench ({@code --game-timeout}): danach {@code destroyForcibly}. */
    public static final int DEFAULT_TIMEOUT_MINUTES = 30;

    /** Frist zwischen {@code destroy()} (SIGTERM) und {@code destroyForcibly()} nach einem Abbruch. */
    private static final int KILL_GRACE_SECONDS = 5;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final Path logDir;
    private final int timeoutMinutes;
    private final AtomicInteger index = new AtomicInteger();

    /** Das Kind der gerade laufenden Partie, damit {@link #cancel()} es erreicht. */
    private volatile Process current;
    /** Ein Abbruch, der eintraf, bevor das Kind ueberhaupt lief - siehe {@link #play}. */
    private volatile boolean cancelled;

    public SubprocessGameRunner() {
        this(ForgeBoot.dataDir().resolve("sparring"), DEFAULT_TIMEOUT_MINUTES);
    }

    public SubprocessGameRunner(Path logDir, int timeoutMinutes) {
        this.logDir = logDir;
        this.timeoutMinutes = timeoutMinutes;
    }

    @Override
    public MatchRecord play(SparringArgs args, String opponent, long seed) {
        // Neue Partie, neuer Abbruchstand. Ein cancel(), das genau hier hereinfaellt, geht verloren -
        // dann laeuft diese eine Partie noch zu Ende, und SparringRun bricht danach ab (sein eigenes
        // Flag steht schon, bevor es uns ruft). Der Lauf endet also in jedem Fall.
        cancelled = false;
        int i = index.getAndIncrement();
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new UncheckedIOException("kann Sparring-Log-Verzeichnis nicht anlegen: " + logDir, e);
        }
        String base = logBaseName(LocalDateTime.now(), i, opponent);
        Path errLog = logDir.resolve(base + ".log");
        Path outLog = logDir.resolve(base + ".out.log");
        List<String> mainArgs = List.of("--sparring-one", Json.toJson(args.job(opponent, seed)));

        ChildJvm.Result result;
        try (BufferedWriter w = Files.newBufferedWriter(outLog, StandardCharsets.UTF_8)) {
            result = ChildJvm.run(mainArgs, RESULT_PREFIX, errLog, line -> {
                try {
                    w.write(line);
                    w.newLine();
                } catch (IOException ignored) {
                    // Log-Datei weg/voll - die Partie selbst laeuft weiter.
                }
            }, timeoutMinutes, TimeUnit.MINUTES, p -> {
                current = p;
                if (cancelled) {
                    // Der Abbruch kam zwischen dem Reset oben und dem Start des Kindes.
                    kill(p);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("kann " + outLog + " nicht schreiben", e);
        } finally {
            current = null;
        }

        if (result.ok()) {
            try {
                return Json.mapper().readValue(result.value(), MatchRecord.class);
            } catch (IOException e) {
                throw new IllegalStateException("Kindprozess lieferte kaputtes JSON: " + e.getMessage(), e);
            }
        }
        String detail = result.timedOut() ? "Zeitlimit nach " + timeoutMinutes + " min, " : "";
        throw new IllegalStateException("Kindprozess: " + detail + "exit=" + result.exit()
                + ", stderr: " + ChildJvm.failureLine(errLog));
    }

    /** Schiesst das Kind der laufenden Partie ab; {@code play} scheitert daraufhin mit Exit ≠ 0. */
    @Override
    public void cancel() {
        cancelled = true;
        kill(current);
    }

    /**
     * Erst {@code destroy()} (SIGTERM - Forge darf seine Dateien noch schliessen), nach
     * {@link #KILL_GRACE_SECONDS} notfalls {@code destroyForcibly()}. Das Warten laeuft auf einem
     * eigenen Daemon-Thread: {@link #cancel()} haengt am WebSocket-Thread und darf nicht blockieren.
     */
    private static void kill(Process p) {
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
        Thread t = new Thread(() -> {
            try {
                if (!p.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }, "sparring-kill-" + p.pid());
        t.setDaemon(true);
        t.start();
    }

    /**
     * Basisname der beiden Protokolldateien einer Partie: {@code sparring-<zeit>-<nr>-<gegner>}.
     * Der Zeitpunkt trennt zwei Laeufe (und zwei Bridge-Starts, bei denen die Nummer wieder bei 0
     * beginnt), die Nummer trennt zwei Partien derselben Sekunde, der Gegner macht den Namen lesbar.
     * Der Deck-Name wird dabei wie in {@code DeckStore.fileName} entschaerft - ein Deck darf "/" oder
     * ":" heissen, ein Dateiname nicht.
     */
    static String logBaseName(LocalDateTime when, int i, String opponent) {
        String name = opponent == null ? "?" : opponent.replaceAll("[^A-Za-z0-9 _\\-\\[\\]().]", "_");
        return "sparring-" + STAMP.format(when) + "-" + i + "-" + name;
    }
}
