package mtgplayer.sparring;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.proc.ChildJvm;
import mtgplayer.protocol.Json;
import mtgplayer.stats.MatchRecord;

/**
 * Spielt jede Sparring-Partie in einem eigenen JVM-Kindprozess ({@code Main --sparring-one <json>},
 * Ergebniszeile {@code SPARRING_RESULT <json MatchRecord>}) - Spec §3: "ein Forge-Absturz darf Kevins
 * laufende Bridge nicht mitnehmen, er spielt ja nebenher". Die Mechanik des Kindprozesses steckt in
 * {@link ChildJvm}, dieselbe Grundlage wie beim Bench.
 *
 * <p>Kein Ergebnis, Exit ≠ 0 oder Ablauf des Zeitlimits werfen - {@link SparringRun} zaehlt die Partie
 * dann als Fehler und macht weiter. stderr und stdout des Kindes liegen unter
 * {@code <dataDir>/sparring/game-&lt;n&gt;.log} bzw. {@code .out.log} zur Nachschau.</p>
 */
public final class SubprocessGameRunner implements GameRunner {

    static final String RESULT_PREFIX = "SPARRING_RESULT ";

    /** Wie beim Bench ({@code --game-timeout}): danach {@code destroyForcibly}. */
    public static final int DEFAULT_TIMEOUT_MINUTES = 30;

    private final Path logDir;
    private final int timeoutMinutes;
    private final AtomicInteger index = new AtomicInteger();

    public SubprocessGameRunner() {
        this(ForgeBoot.dataDir().resolve("sparring"), DEFAULT_TIMEOUT_MINUTES);
    }

    public SubprocessGameRunner(Path logDir, int timeoutMinutes) {
        this.logDir = logDir;
        this.timeoutMinutes = timeoutMinutes;
    }

    @Override
    public MatchRecord play(SparringArgs args, String opponent, long seed) {
        int i = index.getAndIncrement();
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new UncheckedIOException("kann Sparring-Log-Verzeichnis nicht anlegen: " + logDir, e);
        }
        Path errLog = logDir.resolve("game-" + i + ".log");
        Path outLog = logDir.resolve("game-" + i + ".out.log");
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
            }, timeoutMinutes, TimeUnit.MINUTES, null);
        } catch (IOException e) {
            throw new UncheckedIOException("kann " + outLog + " nicht schreiben", e);
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
                + ", letzte stderr-Zeile: " + ChildJvm.lastLine(errLog));
    }
}
