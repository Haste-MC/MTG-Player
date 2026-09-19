package mtgplayer.bench;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;

/**
 * Startet je Spiel {@code java -Xmx4g -cp <classpath> mtgplayer.Main --bench-one <i> <BenchArgs als CLI>},
 * wartet auf die Zeile {@code BENCH_RESULT <json GameRecord>} auf stdout und liest sie als
 * {@link GameRecord}. Grund: ein Forge-eigener Absturz waehrend der Simulation (z. B. {@code GameCopier}
 * "Couldn't map") vergiftet globalen Zustand so, dass Folgespiele in DERSELBEN JVM reihenweise abstuerzen -
 * jedes Spiel in einer eigenen JVM ist die robuste Isolation dagegen, mit dem Nebeneffekt eines frischen
 * Heaps je Spiel. Kein Ergebnis, Exit ≠ 0 oder Ablauf von {@code --game-timeout} (danach
 * {@code destroyForcibly}) zaehlen als Absturz dieses Spiels, nicht als Abbruch des ganzen Laufs.
 */
final class SubprocessRunner implements GameRunner {

    private static final String BENCH_RESULT_PREFIX = "BENCH_RESULT ";

    /**
     * java-Binary des aktuellen JDK, unabhaengig vom Aufruf (Maven bringt sein eigenes ggf. anderes java
     * nicht mit auf den PATH) - {@code ProcessHandle.current().info().command()} liefert den tatsaechlich
     * benutzten Pfad, sonst {@code java.home}/bin/java als Fallback (z. B. wenn das Betriebssystem den
     * Befehl nicht preisgibt).
     */
    private static final String JAVA_BIN = ProcessHandle.current().info().command()
            .orElseGet(() -> System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");

    /**
     * Klassenpfad fuer den Kindprozess. Zwei sehr unterschiedliche Ausgangslagen je nachdem, wie DIESE
     * JVM gestartet wurde:
     * <ul>
     *   <li>{@code mvn test} (Surefire): forkt selbst eine neue JVM fuer die Tests, {@code java.class.path}
     *       zeigt hier auf den vollstaendigen Testklassenpfad (auf Linux ist die Kommandozeile lang genug,
     *       dass Surefire keine Manifest-Jar-Indirektion braucht) und enthaelt forge-gui/forge-ai/jackson -
     *       direkt verwendbar.</li>
     *   <li>{@code mvn exec:java} (ExecJavaMojo): forkt KEINE neue JVM, sondern laedt {@code mtgplayer.Main}
     *       in DIESER Maven-JVM ueber einen selbstgebauten {@link URLClassLoader}. {@code java.class.path}
     *       zeigt dann nur auf Mavens eigene Launcher-Jars (plexus-classworlds & co.), nicht auf das
     *       Projekt - ein Kindprozess mit diesem Classpath faende {@code mtgplayer.Main} gar nicht. In
     *       diesem Fall stattdessen die URLs von {@code Bench.class.getClassLoader()} nehmen: das ist
     *       unter exec:java genau der URLClassLoader, den ExecJavaMojo aus dem Projekt-Klassenpfad baut
     *       und mit dem es Main laedt (auch dessen Elternklassen wie Bench haengen daran).</li>
     * </ul>
     * Erkennung: enthaelt {@code java.class.path} einen Eintrag, der nach forge-gui/forge-ai/jackson-databind
     * oder einem {@code classes}-Verzeichnis aussieht, gilt er als vollstaendig; sonst der ClassLoader-Pfad,
     * falls der seinerseits vollstaendig aussieht - sonst bestmoeglicher Rueckfall auf java.class.path
     * (z. B. Surefires {@code surefirebooter*.jar}: dessen Manifest-Class-Path liest der NEUE java-Launcher
     * selbst beim Start, ganz ohne unser Zutun, falls Surefire doch einmal eine Manifest-Jar braucht).
     */
    private static final String CLASSPATH = resolveClasspath();

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

        List<String> cmd = new ArrayList<>();
        cmd.add(JAVA_BIN);
        cmd.add("-Xmx4g");
        // Explizit statt auf das geerbte Arbeitsverzeichnis zu vertrauen - ForgeBoot.assetsDir() loest
        // sonst relativ zum cwd des Kindprozesses auf.
        cmd.add("-Dmtgplayer.assets=" + ForgeBoot.assetsDir());
        cmd.add("-cp");
        cmd.add(CLASSPATH);
        cmd.add("mtgplayer.Main");
        cmd.add("--bench-one");
        cmd.add(Integer.toString(i));
        cmd.addAll(cliArgs(args));

        long t0 = System.currentTimeMillis();
        Process p;
        try {
            p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(logFile.toFile())
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("kann Kindprozess fuer Spiel " + i + " nicht starten: " + cmd, e);
        }
        current = p;
        progress.printf("Spiel %d/%d (Kindprozess pid %d)%n", i + 1, args.games(), p.pid());

        String[] result = {null};
        int[] simErrors = {0};
        Path outLog = logFile.resolveSibling("game-" + i + ".out.log");
        Thread stdout = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 java.io.BufferedWriter w = Files.newBufferedWriter(outLog, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith(BENCH_RESULT_PREFIX)) {
                        result[0] = line.substring(BENCH_RESULT_PREFIX.length());
                    } else if (!line.startsWith("Warning: default")) {
                        // Forge-Spiellog und Forge-Fehlermeldungen (System.out) fuer die Nachschau aufheben;
                        // "Couldn't map"/"Game copy error" = Simulation defekt (GameCopier), Forge faengt das
                        // meist ab und die Sim-KI spielt dann still nichts mehr - siehe GameRecord.simErrors.
                        w.write(line);
                        w.newLine();
                        if (line.startsWith("Couldn't map") || line.contains("Game copy error")) {
                            simErrors[0]++;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Kindprozess weg/gekillt waehrend wir lesen - result bleibt null, unten als Absturz gewertet.
            }
        }, "bench-subprocess-stdout-" + i);
        stdout.setDaemon(true);
        stdout.start();

        boolean timedOut;
        try {
            timedOut = !p.waitFor(gameTimeoutMinutes, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            timedOut = true;
        }
        if (timedOut) {
            p.destroyForcibly();
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            stdout.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        current = null;
        long millis = System.currentTimeMillis() - t0;

        int exit = p.exitValue();
        if (!timedOut && exit == 0 && result[0] != null) {
            try {
                return Json.mapper().readValue(result[0], GameRecord.class).withSimErrors(simErrors[0]);
            } catch (IOException e) {
                // Kaputtes JSON auf stdout - faellt unten durch zu Crash.
            }
        }
        String lastStderr = lastLine(logFile);
        String detail = timedOut ? "Timeout nach " + gameTimeoutMinutes + " min, " : "";
        RuntimeException cause = new RuntimeException("Kindprozess: " + detail + "exit=" + exit
                + ", letzte stderr-Zeile: " + lastStderr);
        return GameRecord.crash(i, seed, "?", cause, millis);
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

    private static String lastLine(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int j = lines.size() - 1; j >= 0; j--) {
                if (!lines.get(j).isBlank()) {
                    return lines.get(j);
                }
            }
            return "(leer)";
        } catch (IOException e) {
            return "(Log nicht lesbar: " + e.getMessage() + ")";
        }
    }

    private static String resolveClasspath() {
        String cp = System.getProperty("java.class.path", "");
        if (looksLikeProjectClasspath(cp)) {
            return cp;
        }
        ClassLoader cl = Bench.class.getClassLoader();
        if (cl instanceof URLClassLoader u) {
            String joined = joinUrls(u.getURLs());
            if (looksLikeProjectClasspath(joined)) {
                return joined;
            }
        }
        return cp;
    }

    private static boolean looksLikeProjectClasspath(String cp) {
        if (cp == null || cp.isBlank()) {
            return false;
        }
        for (String entry : cp.split(File.pathSeparator)) {
            Path p = Path.of(entry);
            String name = p.getFileName() == null ? entry : p.getFileName().toString();
            if (name.contains("forge-gui") || name.contains("forge-ai") || name.contains("jackson-databind")
                    || name.equals("classes")) {
                return true;
            }
        }
        return false;
    }

    private static String joinUrls(URL[] urls) {
        StringBuilder sb = new StringBuilder();
        for (URL u : urls) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparatorChar);
            }
            try {
                sb.append(Path.of(u.toURI()));
            } catch (Exception e) {
                sb.append(u.getPath());
            }
        }
        return sb.toString();
    }
}
