package mtgplayer.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mtgplayer.proc.ChildJvm;
import mtgplayer.protocol.Json;

/**
 * Startet je Spiel {@code java -Xmx4g -cp <classpath> mtgplayer.Main --bench-one <i> <BenchArgs als CLI>},
 * wartet auf die Zeile {@code BENCH_RESULT <json GameRecord>} auf stdout und liest sie als
 * {@link GameRecord}. Der Kindprozess selbst (java-Binary, Klassenpfad, weitergereichte Properties,
 * Zeitlimit, Lesen der Ergebniszeile) steckt in {@link ChildJvm} - dieselbe Grundlage benutzt das
 * Sparring ({@code mtgplayer.sparring.SubprocessGameRunner}).
 *
 * <p>Grund fuer die eigene JVM je Spiel: ein Forge-eigener Absturz waehrend der Simulation (z. B.
 * {@code GameCopier} "Couldn't map") vergiftet globalen Zustand so, dass Folgespiele in DERSELBEN JVM
 * reihenweise abstuerzen. Kein Ergebnis, Exit ≠ 0 oder Ablauf von {@code --game-timeout} (danach
 * {@code destroyForcibly}) zaehlen als Absturz dieses Spiels, nicht als Abbruch des ganzen Laufs.</p>
 */
final class SubprocessRunner implements GameRunner {

    private static final String BENCH_RESULT_PREFIX = "BENCH_RESULT ";

    private final PrintStream progress;
    private final int gameTimeoutMinutes;
    private volatile Process current;

    SubprocessRunner(PrintStream progress, int gameTimeoutMinutes) {
        this.progress = progress;
        this.gameTimeoutMinutes = gameTimeoutMinutes;
    }

    @Override
    public GameRecord play(BenchArgs args, int i) {
        long seed = args.seed() + i;
        try {
            Files.createDirectories(args.out());
        } catch (IOException e) {
            throw new UncheckedIOException("kann Bench-Ausgabeverzeichnis nicht anlegen: " + args.out(), e);
        }
        Path logFile = args.out().resolve("game-" + i + ".log");
        Path outLog = args.out().resolve("game-" + i + ".out.log");

        List<String> mainArgs = new ArrayList<>();
        mainArgs.add("--bench-one");
        mainArgs.add(Integer.toString(i));
        mainArgs.addAll(cliArgs(args));

        int[] simErrors = {0};
        ChildJvm.Result result;
        // Forge-Spiellog und Forge-Fehlermeldungen (System.out) fuer die Nachschau aufheben;
        // "Couldn't map"/"Game copy error" = Simulation defekt (GameCopier), Forge faengt das
        // meist ab und die Sim-KI spielt dann still nichts mehr - siehe GameRecord.simErrors.
        try (BufferedWriter w = Files.newBufferedWriter(outLog, StandardCharsets.UTF_8)) {
            result = ChildJvm.run(mainArgs, BENCH_RESULT_PREFIX, logFile, line -> {
                try {
                    w.write(line);
                    w.newLine();
                } catch (IOException ignored) {
                    // Log-Datei weg/voll - das Spiel selbst laeuft weiter, sein Ergebnis zaehlt trotzdem.
                }
                if (line.startsWith("Couldn't map") || line.contains("Game copy error")) {
                    simErrors[0]++;
                }
            }, gameTimeoutMinutes, TimeUnit.MINUTES, p -> {
                current = p;
                progress.printf("Spiel %d/%d (Kindprozess pid %d)%n", i + 1, args.games(), p.pid());
            });
        } catch (IOException e) {
            throw new UncheckedIOException("kann " + outLog + " nicht schreiben", e);
        } finally {
            current = null;
        }

        if (result.ok()) {
            try {
                return Json.mapper().readValue(result.value(), GameRecord.class).withSimErrors(simErrors[0]);
            } catch (IOException e) {
                // Kaputtes JSON auf stdout - faellt unten durch zu Crash.
            }
        }
        String detail = result.timedOut() ? "Timeout nach " + gameTimeoutMinutes + " min, " : "";
        RuntimeException cause = new RuntimeException("Kindprozess: " + detail + "exit=" + result.exit()
                + ", letzte stderr-Zeile: " + ChildJvm.lastLine(logFile));
        return GameRecord.crash(i, seed, "?", cause, result.millis());
    }

    @Override
    public void shutdown() {
        Process p = current;
        if (p != null) {
            p.destroy();
        }
    }

    /** {@link BenchArgs} als CLI-Argumente, so wie {@link BenchArgs#parse} sie wieder einliest -
     *  {@code --bench-one} braucht dieselben Parameter wie der Elternlauf, ausser dem Spielindex, der
     *  separat davor steht. Jedes Element landet als eigener argv-Eintrag (kein Shell-Tokenizing wie bei
     *  {@code -Dexec.args}), Precon-Namen mit Leerzeichen brauchen deshalb kein Quoting. */
    private static List<String> cliArgs(BenchArgs args) {
        List<String> out = new ArrayList<>();
        out.add("--games");
        out.add(Integer.toString(args.games()));
        out.add("--a");
        out.add(args.a().spec());
        out.add("--b");
        out.add(args.b().spec());
        out.add("--deck-a");
        out.add(args.deckA());
        out.add("--deck-b");
        out.add(args.deckB());
        out.add("--turns");
        out.add(Integer.toString(args.turns()));
        out.add("--timeout");
        out.add(Integer.toString(args.timeout()));
        out.add("--seed");
        out.add(Long.toString(args.seed()));
        out.add("--out");
        out.add(args.out().toString());
        out.add("--game-timeout");
        out.add(Integer.toString(args.gameTimeoutMinutes()));
        return out;
    }
}
